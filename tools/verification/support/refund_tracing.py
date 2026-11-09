"""Tempo trace judgement for verify-refund-tracing.py (decision 28, D-050).

A refund trace passes only with real evidence: the Tempo lookup must succeed and return spans,
exactly one root, every required service, no span whose parent is missing, and the scenario's
Payment PG client spans (worker lookups/resends under ``payment.process``). Collection is awaited
with a deadline; an empty or failed lookup is a failure, never a pass.
"""
import base64
import json
import time
import urllib.request

REQUIRED_SERVICES = ("gateway", "order", "license", "payment", "settlement")
WORKER_SPAN = "payment.process"
CLIENT_KINDS = ("SPAN_KIND_CLIENT", 3)


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
                spans.append(dict(service=attributes.get("service.name"), name=span["name"], kind=span.get("kind"),
                                  spanId=hex_id(span.get("spanId")), parentSpanId=hex_id(span.get("parentSpanId")),
                                  url=tags.get("http.url") or tags.get("url.full") or tags.get("uri"),
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


def pg_calls(spans, payment_id):
    """Payment HTTP client spans to the PG for this paymentId: (cancels, lookups), each with a worker flag."""
    by_id = {s["spanId"]: s for s in spans}
    cancels, lookups = [], []
    for span in spans:
        if span["service"] != "payment" or span["kind"] not in CLIENT_KINDS or not span["url"] or "/payments/" + payment_id not in span["url"]:
            continue
        call = dict(span=span, worker=under_worker(span, by_id))
        (cancels if "/cancel" in span["url"] else lookups).append(call)
    return cancels, lookups


def trace_problems(spans, scenario, payment_id):
    """Every reason this refund trace is not complete evidence; empty means it passes."""
    if not spans:
        return ["no spans"]
    summary = describe(spans)
    problems = []
    if len(summary["roots"]) != 1:
        problems.append("expected exactly one root, found " + str(len(summary["roots"])) + " " + str(summary["roots"]))
    missing = [name for name in REQUIRED_SERVICES if name not in summary["services"]]
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
    return problems


def await_trace(fetch, trace_id, scenario, payment_id, seconds=90, interval=3, clock=time.monotonic, sleep=time.sleep):
    """Poll Tempo until the trace is complete evidence; raise with the last problems at the deadline."""
    deadline = clock() + seconds
    while True:
        try:
            spans = fetch(trace_id)
            problems = trace_problems(spans, scenario, payment_id)
        except TraceUnavailable as failure:
            spans, problems = [], [str(failure)]
        if not problems:
            return spans
        if clock() >= deadline:
            raise AssertionError(scenario + " trace " + trace_id + " incomplete: " + "; ".join(problems))
        sleep(interval)
