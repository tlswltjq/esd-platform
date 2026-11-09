"""Tempo trace judgement for verify-refund-tracing.py (decision 28, D-050).

A refund trace passes only with real evidence: the Tempo lookup must succeed and return spans,
exactly one root, every required service (``required_services``: Settlement is not required after a confirmed PG failure),
no span whose parent is missing, and the scenario's
Payment PG client spans (worker lookups/resends under ``payment.process``). Collection is awaited
with a deadline; an empty or failed lookup is a failure, never a pass. A passing snapshot is accepted only
after STABLE_READS consecutive reads return the same span ids, so a late export (an orphan span) is still
judged. PG calls are identified by exact path segments of the Payment PortOne adapters:
GET /payments/{paymentId} (preflight and refund lookup) and POST /payments/{paymentId}/cancel.
"""
import base64
import json
import re
import time
import urllib.parse
import urllib.request

REQUIRED_SERVICES = ("gateway", "order", "license", "payment", "settlement")
# Confirmed PG failure: the refund ends FAILED with the license restored and money untouched, so Settlement
# records nothing (it may still open a consumer span while skipping Payment events; that is not required).
RESTORATION_SERVICES = ("gateway", "order", "license", "payment")
WORKER_SPAN = "payment.process"
CLIENT_KINDS = ("SPAN_KIND_CLIENT", 3)
STABLE_READS = 3

# Export settings the tracing verification stack pins for every app (fake_portone_stack.TRACE_EXPORT_ENV;
# Spring Boot 3.5 defaults are 5 s / 30 s / 10 s and are not changed for operation).
MAX_QUEUE_SIZE = 2048        # management.tracing.opentelemetry.export.max-queue-size (Boot default 2048)
MAX_BATCH_SIZE = MAX_QUEUE_SIZE  # ...export.max-batch-size (Boot default 512): one export drains the whole queue
SCHEDULE_DELAY_SECONDS = 1   # management.tracing.opentelemetry.export.schedule-delay (BatchSpanProcessor)
EXPORT_TIMEOUT_SECONDS = 5   # management.tracing.opentelemetry.export.timeout
OTLP_TIMEOUT_SECONDS = 5     # management.otlp.tracing.timeout (HTTP exporter, connect timeout 2 s)
CONSUMER_MARGIN_SECONDS = 5  # a consumer span ends just after the offset commit that made the group lag 0
TEMPO_MARGIN_SECONDS = 2     # Tempo answers trace-by-id from ingester live traces right after the push


def export_bound(schedule_delay=SCHEDULE_DELAY_SECONDS, export_timeout=EXPORT_TIMEOUT_SECONDS, otlp_timeout=OTLP_TIMEOUT_SECONDS):
    """Longest time from a span's end until its export finishes.

    Holds only while max-batch-size == max-queue-size and the queue never fills: then every tick
    (<= schedule delay, possibly after one export already in flight) exports the whole queue in one
    batch, bounded by min(processor export timeout, OTLP HTTP timeout). A backlog of several batches or a
    full queue (spans dropped silently) is outside this bound. Drops are not observable here: Boot 3.5.6
    passes a MeterProvider to BatchSpanProcessor only when an SdkMeterProvider bean exists, and the apps
    define none, so the processor's dropped-span counter is a no-op. A dropped parent or a required
    service/PG span still fails the judgement; a dropped leaf span is the remaining risk.
    """
    per_export = min(export_timeout, otlp_timeout)
    return schedule_delay + 2 * per_export


def observation_window(consumer_margin=CONSUMER_MARGIN_SECONDS, tempo_margin=TEMPO_MARGIN_SECONDS, **export):
    """Seconds to keep watching a trace after business completion; longer than any span can be late."""
    return consumer_margin + export_bound(**export) + tempo_margin


class TraceUnavailable(RuntimeError):
    """Tempo could not be read or returned no spans for the trace."""


def hex_id(value):
    if not value:
        return ""
    if len(value) in (16, 32) and all(c in "0123456789abcdef" for c in value):
        return value
    return base64.b64decode(value).hex()


def parse_trace(trace):
    spans = []
    for batch in trace.get("batches") or trace.get("resourceSpans") or []:
        attributes = {a["key"]: a["value"].get("stringValue") for a in batch.get("resource", {}).get("attributes", [])}
        for scope in batch.get("scopeSpans") or batch.get("instrumentationLibrarySpans") or []:
            for span in scope.get("spans", []):
                tags = {a["key"]: next(iter(a["value"].values()), None) for a in span.get("attributes", [])}
                name = span["name"]
                method = tags.get("http.request.method") or tags.get("http.method") or tags.get("method")
                if not method and name.lower().startswith("http "):
                    method = name.split(" ", 2)[1]
                spans.append(dict(service=attributes.get("service.name"), name=name, kind=span.get("kind"),
                                  spanId=hex_id(span.get("spanId")), parentSpanId=hex_id(span.get("parentSpanId")),
                                  url=tags.get("http.url") or tags.get("url.full") or tags.get("http.target"),
                                  method=method.upper() if method else None,
                                  start=int(span.get("startTimeUnixNano", 0)) / 1e9))
    return spans


def fetch_trace(tempo_url, trace_id):
    """Spans of one trace; raises TraceUnavailable on any lookup error or an empty trace."""
    try:
        with urllib.request.urlopen(tempo_url + "/api/traces/" + trace_id, timeout=10) as response:
            spans = parse_trace(json.load(response))
    except Exception as failure:
        raise TraceUnavailable("Tempo lookup failed for " + trace_id + ": " + type(failure).__name__ + " " + str(failure)) from failure
    if not spans:
        raise TraceUnavailable("Tempo returned no spans for " + trace_id)
    return spans


def describe(spans):
    ids = {s["spanId"] for s in spans}
    roots = [s for s in spans if not s["parentSpanId"]]
    orphans = [s for s in spans if s["parentSpanId"] and s["parentSpanId"] not in ids]
    return dict(spanCount=len(spans), services=sorted({s["service"] or "?" for s in spans}),
                roots=[str(s["service"]) + ":" + s["name"] for s in roots],
                missingParents=[str(s["service"]) + ":" + s["name"] for s in orphans])


def under_worker(span, by_id):
    current = span
    while current["parentSpanId"]:
        current = by_id.get(current["parentSpanId"])
        if current is None:
            return False
        if current["service"] == "payment" and current["name"] == WORKER_SPAN:
            return True
    return False


def pg_call(span, payment_id):
    """'cancel' for POST /payments/{id}/cancel, 'lookup' for GET /payments/{id}, else None (exact segments)."""
    if span["service"] != "payment" or span["kind"] not in CLIENT_KINDS or not span.get("url"):
        return None
    segments = [urllib.parse.unquote(part) for part in urllib.parse.urlsplit(span["url"]).path.split("/") if part]
    method = span.get("method")
    if segments == ["payments", payment_id] and method == "GET":
        return "lookup"
    if segments == ["payments", payment_id, "cancel"] and method == "POST":
        return "cancel"
    return None


def pg_calls(spans, payment_id):
    """Payment HTTP client spans to the PG for this paymentId: (cancels, lookups), each with a worker flag."""
    by_id = {s["spanId"]: s for s in spans}
    cancels, lookups = [], []
    for span in spans:
        kind = pg_call(span, payment_id)
        if kind is None:
            continue
        call = dict(span=span, worker=under_worker(span, by_id))
        (cancels if kind == "cancel" else lookups).append(call)
    return cancels, lookups


def required_services(scenario):
    return RESTORATION_SERVICES if scenario == "restoration" else REQUIRED_SERVICES


def trace_problems(spans, scenario, payment_id):
    """Every reason this refund trace is not complete evidence; empty means it passes."""
    if not spans:
        return ["no spans"]
    summary = describe(spans)
    problems = []
    if len(summary["roots"]) != 1:
        problems.append("expected exactly one root, found " + str(len(summary["roots"])) + " " + str(summary["roots"]))
    missing = [name for name in required_services(scenario) if name not in summary["services"]]
    if missing:
        problems.append("missing services " + str(missing))
    if summary["missingParents"]:
        problems.append("spans without parent " + str(summary["missingParents"]))
    cancels, lookups = pg_calls(spans, payment_id)
    if not cancels:
        problems.append("no PG cancel span")
    if not lookups:
        problems.append("no PG lookup span")
    if scenario == "lost-response" and not any(call["worker"] for call in lookups):
        problems.append("no worker PG lookup under " + WORKER_SPAN)
    if scenario == "pg-error":
        if len(cancels) < 2:
            problems.append("expected the first PG cancel and a resend, found " + str(len(cancels)))
        if not any(call["worker"] for call in cancels):
            problems.append("no worker PG resend under " + WORKER_SPAN)
    if scenario == "restoration":
        if len(cancels) != 1:
            # The FAILED answer is recorded and confirmed by the lookup right after it; nothing is sent again.
            problems.append("expected exactly one PG cancel before the confirmed failure, found " + str(len(cancels)))
        elif lookups:
            # RequestRefundCancellation: preflight GET, POST cancel, then reconcile GET that confirms the failure.
            sent = cancels[0]["span"]["start"]
            if not any(call["span"]["start"] < sent for call in lookups):
                problems.append("no PG preflight lookup before the cancel")
            if not any(call["span"]["start"] > sent for call in lookups):
                problems.append("no PG lookup after the cancel confirming the failure")
    return problems


def await_trace(fetch, trace_id, scenario, payment_id, seconds=120, interval=3, stable_reads=STABLE_READS,
                clock=time.monotonic, sleep=time.sleep, quiet_until=None):
    """Poll Tempo until the trace is complete evidence and stable; raise with the last problems at the deadline.

    A snapshot counts only when it passes trace_problems; it is returned after ``stable_reads``
    consecutive passing reads with the same span ids. Any change restarts the count, so every span
    exported late is judged in the final snapshot.
    """
    deadline = clock() + seconds
    previous, same = None, 0
    while True:
        try:
            spans = fetch(trace_id)
            problems = trace_problems(spans, scenario, payment_id)
        except TraceUnavailable as failure:
            spans, problems = [], [str(failure)]
        ids = tuple(sorted(s["spanId"] for s in spans))
        if problems or (quiet_until is not None and clock() < quiet_until):
            # Reads inside the observation window are judged but never counted toward stability.
            previous, same = None, 0
        elif ids == previous:
            same += 1
        else:
            previous, same = ids, 1
        # Accept only after the observation window (business completion + observation_window()) has passed,
        # so a span exported as late as the bound allows is still judged.
        if same >= stable_reads:
            return spans
        if clock() >= deadline:
            reason = "; ".join(problems) if problems else "spans still changing after " + str(seconds) + " s (" + str(same) + "/" + str(stable_reads) + " stable reads)"
            raise AssertionError(scenario + " trace " + trace_id + " incomplete: " + reason)
        sleep(interval)


# Consumer groups of the refund flow, read from the code constants (verify-refund-tracing waits for lag 0).
REFUND_GROUP_SOURCES = (
    "apps/order/src/main/java/com/stove/order/core/service/ApplyRefundSuspension.java",
    "apps/order/src/main/java/com/stove/order/core/service/ApplyRefundPreparation.java",
    "apps/order/src/main/java/com/stove/order/core/service/ApplyRefundPaymentConfirmation.java",
    "apps/license/src/main/java/com/stove/license/core/service/ApplyLicenseSuspension.java",
    "apps/license/src/main/java/com/stove/license/core/service/ApplyPurchaseRefund.java",
    "apps/payment/src/main/java/com/stove/payment/core/service/ReceiveRefundExecution.java",
    "apps/settlement/src/main/java/com/stove/settlement/core/service/RecordConfirmedRefund.java",
    # Restoration after a confirmed PG failure (RefundExecutionFailed -> LicenseRestorationRequested -> LicenseRestored).
    "apps/order/src/main/java/com/stove/order/core/service/ApplyRefundRestoration.java",
    "apps/license/src/main/java/com/stove/license/core/service/ApplyLicenseRestoration.java",
)
GROUP_CONSTANT = re.compile(r'CONSUMER_GROUP\s*=\s*"([^"]+)"')


def refund_consumer_groups(repo):
    groups = []
    for source in REFUND_GROUP_SOURCES:
        found = GROUP_CONSTANT.findall((repo / source).read_text())
        if len(found) != 1:
            raise RuntimeError("Expected one CONSUMER_GROUP constant in " + source)
        groups.append(found[0])
    return groups


SETTLED_STATES = ("Stable", "Empty")
GROUP_STATES = SETTLED_STATES + ("PreparingRebalance", "CompletingRebalance", "Dead", "Unknown")
NO_MEMBERS = re.compile(r"^Consumer group '([^']+)' has no active members\.$")


def group_snapshot(run):
    """Three kafka-consumer-groups.sh reads: --list, --describe --all-groups --state, --describe --all-groups.

    ``run(args)`` returns (returncode, stdout, stderr); every stream is judged by lag_problems.
    """
    return dict(list=run(["--list"]), state=run(["--describe", "--all-groups", "--state"]), offsets=run(["--describe", "--all-groups"]))


def lag_problems(snapshot, required):
    """Every reason the broker's consumer groups are not provably caught up; empty means observation may start.

    All groups count, because a group on the same topic still opens a consumer span in the refund trace
    while it skips the event. The group list, the state table and the offset table must agree; every group
    must be Stable or Empty (no rebalance); every offset row needs a numeric partition and lag 0 (an
    uncommitted offset passes only on an empty log). Any stderr except "has no active members" for an Empty
    group, any non-zero exit and any line that cannot be parsed is a problem, so the caller reads again.
    """
    problems = []
    for name in ("list", "state", "offsets"):
        code = snapshot[name][0]
        if code != 0:
            problems.append(name + " exited " + str(code))
    listed = [line.strip() for line in snapshot["list"][1].splitlines() if line.strip()]
    if snapshot["list"][2].strip():
        problems.append("list stderr: " + snapshot["list"][2].strip())
    states, no_members = {}, set()
    for line in snapshot["state"][1].splitlines() + snapshot["state"][2].splitlines():
        text = line.strip()
        fields = text.split()
        if not text or (fields[0] == "GROUP" and "COORDINATOR" in fields):
            continue
        matched = NO_MEMBERS.match(text)
        if matched:
            no_members.add(matched.group(1))
        elif len(fields) >= 5 and fields[-2] in GROUP_STATES and fields[-1].isdigit():
            states[fields[0]] = fields[-2]
        else:
            problems.append("unparsable state output: " + text)
    offsets = {}
    for line in snapshot["offsets"][1].splitlines():
        text = line.strip()
        fields = text.split()
        if not text or (fields[0] == "GROUP" and "PARTITION" in fields):
            continue
        if NO_MEMBERS.match(text):
            no_members.add(NO_MEMBERS.match(text).group(1))
            continue
        if len(fields) < 6:
            problems.append("unparsable offset output: " + text)
            continue
        group, topic, partition, current, end, lag = fields[:6]
        offsets.setdefault(group, 0)
        if not partition.isdigit():
            problems.append(group + " has a row without a partition (" + topic + "/" + partition + "), assignment unverifiable")
            continue
        offsets[group] += 1
        if not (lag == "0" or (current == "-" and end == "0")):
            problems.append(group + " " + topic + "-" + partition + " lag " + lag + " (offset " + current + "/" + end + ")")
    for line in snapshot["offsets"][2].splitlines():
        text = line.strip()
        matched = NO_MEMBERS.match(text)
        if not text:
            continue
        if matched:
            no_members.add(matched.group(1))
        else:
            problems.append("offsets stderr: " + text)
    for group in listed:
        state = states.get(group)
        if state is None:
            problems.append(group + " has no state")
        elif state not in SETTLED_STATES:
            problems.append(group + " is " + state)
        if offsets.get(group, 0) == 0:
            problems.append(group + " missing from offsets")
    for group in sorted(set(states) - set(listed)):
        problems.append(group + " has a state but is not listed")
    for group in sorted(set(offsets) - set(listed)):
        problems.append(group + " has offsets but is not listed")
    for group in sorted(no_members):
        if states.get(group) != "Empty":
            problems.append(group + " reported without members but state " + str(states.get(group)))
    missing = [group for group in required if group not in listed]
    if missing:
        problems.insert(0, "consumer groups not listed " + str(missing))
    return problems
