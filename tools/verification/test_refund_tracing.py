import http.server
import json
import threading
import unittest

from support import refund_tracing
from support.refund_tracing import TraceUnavailable, await_trace, fetch_trace, parse_trace, pg_call, trace_problems

PAYMENT = "pay-1"
PG = "http://127.0.0.1:1/payments/" + PAYMENT


def span(service, name, span_id, parent="", kind="SPAN_KIND_INTERNAL", url=None, method=None):
    return dict(service=service, name=name, kind=kind, spanId=span_id, parentSpanId=parent, url=url, method=method, start=0)


def client(span_id, parent, url, method=None):
    method = method or ("POST" if url.endswith("/cancel") else "GET")
    return span("payment", "http " + method.lower(), span_id, parent, "SPAN_KIND_CLIENT", url, method)


def complete(scenario):
    """A refund trace with every required service and the scenario's PG spans."""
    spans = [span("gateway", "http post", "g"), span("order", "http post /refund", "o", "g"),
             span("license", "stove.order.v1 receive", "l", "o"), span("payment", "stove.order.v1 receive", "p", "l"),
             span("settlement", "stove.order.v1 receive", "s", "o"),
             client("c1", "p", PG + "/cancel"), client("g1", "p", PG + "?storeId=s")]
    if scenario in ("lost-response", "pg-error"):
        spans.append(span("payment", "payment.process", "w", "p"))
        spans.append(client("g2", "w", PG + "?storeId=s"))
    if scenario == "pg-error":
        spans.append(client("c2", "w", PG + "/cancel"))
    return spans


class TraceProblemsTest(unittest.TestCase):
    def test_complete_traces_pass_for_every_scenario(self):
        for scenario in ("normal", "lost-response", "pg-error"):
            self.assertEqual(trace_problems(complete(scenario), scenario, PAYMENT), [], scenario)

    def test_empty_trace_fails(self):
        self.assertEqual(trace_problems([], "normal", PAYMENT), ["no spans"])

    def test_two_roots_fail(self):
        spans = complete("normal") + [span("payment", "http get", "x")]
        self.assertTrue(any("exactly one root" in p for p in trace_problems(spans, "normal", PAYMENT)))

    def test_missing_service_fails(self):
        spans = [s for s in complete("normal") if s["service"] != "settlement"]
        self.assertIn("missing services ['settlement']", trace_problems(spans, "normal", PAYMENT))

    def test_missing_parent_fails(self):
        spans = complete("normal") + [span("order", "stove.payment.v1 receive", "y", "absent")]
        self.assertTrue(any("without parent" in p for p in trace_problems(spans, "normal", PAYMENT)))

    def test_missing_pg_spans_fail(self):
        spans = [s for s in complete("normal") if s["kind"] != "SPAN_KIND_CLIENT"]
        self.assertEqual(trace_problems(spans, "normal", PAYMENT), ["no PG cancel span", "no PG lookup span"])

    def test_payment_id_prefix_collision_does_not_count(self):
        # Validating pay-1 must not accept calls for pay-10.
        spans = [dict(s, url=s["url"].replace("/payments/" + PAYMENT, "/payments/" + PAYMENT + "0")) if s["url"] else s
                 for s in complete("normal")]
        self.assertEqual(trace_problems(spans, "normal", PAYMENT), ["no PG cancel span", "no PG lookup span"])
        self.assertIsNone(pg_call(client("x", "p", "http://h/payments/pay-10/cancel"), PAYMENT))
        self.assertEqual(pg_call(client("x", "p", "http://h/payments/pay-1/cancel"), PAYMENT), "cancel")

    def test_other_api_sub_paths_and_methods_are_not_pg_refund_calls(self):
        for url, method in [(PG + "/transactions?storeId=s", "GET"), (PG + "/confirm", "POST"), (PG + "/cancel/extra", "POST"),
                            (PG + "/cancel", "GET"), (PG + "?storeId=s", "POST"), ("http://h/v2/payments/" + PAYMENT, "GET"),
                            ("http://h/payments/" + PAYMENT + "/billing-key", "GET")]:
            self.assertIsNone(pg_call(client("x", "p", url, method), PAYMENT), url + " " + method)
        self.assertEqual(pg_call(client("x", "p", "/payments/" + PAYMENT + "?storeId=s", "GET"), PAYMENT), "lookup")  # http.target
        not_payment = dict(client("x", "p", PG + "/cancel"), service="settlement")
        self.assertIsNone(pg_call(not_payment, PAYMENT))
        server = dict(client("x", "p", PG + "/cancel"), kind="SPAN_KIND_SERVER")
        self.assertIsNone(pg_call(server, PAYMENT))

    def test_sub_paths_are_not_counted_as_lookups(self):
        spans = [dict(s, url=PG + "/transactions?storeId=s") if s["spanId"] == "g1" else s for s in complete("normal")]
        self.assertEqual(trace_problems(spans, "normal", PAYMENT), ["no PG lookup span"])

    def test_pg_spans_of_another_payment_do_not_count(self):
        spans = [dict(s, url=s["url"].replace(PAYMENT, "other")) if s["url"] else s for s in complete("normal")]
        self.assertEqual(trace_problems(spans, "normal", PAYMENT), ["no PG cancel span", "no PG lookup span"])

    def test_lost_response_requires_a_worker_lookup_under_payment_process(self):
        spans = [s for s in complete("lost-response") if s["spanId"] != "g2"]
        self.assertEqual(trace_problems(spans, "lost-response", PAYMENT), ["no worker PG lookup under payment.process"])

    def test_pg_error_requires_a_worker_resend_under_payment_process(self):
        spans = [dict(s, parentSpanId="p") if s["spanId"] == "c2" else s for s in complete("pg-error")]
        self.assertEqual(trace_problems(spans, "pg-error", PAYMENT), ["no worker PG resend under payment.process"])
        spans = [s for s in complete("pg-error") if s["spanId"] != "c2"]
        self.assertIn("expected the first PG cancel and a resend, found 1", trace_problems(spans, "pg-error", PAYMENT))


class FetchAndAwaitTest(unittest.TestCase):
    def serve(self, status, body):
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                raw = body.encode()
                self.send_response(status)
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        return "http://127.0.0.1:" + str(server.server_port)

    def test_lookup_error_is_unavailable_not_empty(self):
        with self.assertRaises(TraceUnavailable):
            fetch_trace(self.serve(404, "trace not found"), "a" * 32)
        with self.assertRaises(TraceUnavailable):
            fetch_trace("http://127.0.0.1:1", "a" * 32)

    def test_empty_response_is_unavailable(self):
        for body in ('{"batches": []}', "{}"):
            with self.assertRaises(TraceUnavailable):
                fetch_trace(self.serve(200, body), "a" * 32)

    def test_parses_otlp_json_with_base64_ids(self):
        trace = {"batches": [{"resource": {"attributes": [{"key": "service.name", "value": {"stringValue": "payment"}}]},
                              "scopeSpans": [{"spans": [{"name": "http get", "kind": "SPAN_KIND_CLIENT", "spanId": "AQIDBAUGBwg=",
                                                         "attributes": [{"key": "http.url", "value": {"stringValue": PG}}]}]}]}]}
        spans = fetch_trace(self.serve(200, json.dumps(trace)), "a" * 32)
        self.assertEqual((spans[0]["service"], spans[0]["spanId"], spans[0]["parentSpanId"], spans[0]["url"]),
                         ("payment", "0102030405060708", "", PG))

    @staticmethod
    def sequence(*answers):
        """fetch() returning each answer in turn, then the last one forever."""
        queue = list(answers)

        def fetch(trace_id):
            answer = queue.pop(0) if len(queue) > 1 else queue[0]
            if isinstance(answer, Exception):
                raise answer
            return answer
        return fetch

    @staticmethod
    def fake_clock():
        now = [0.0]
        return dict(clock=lambda: now[0], sleep=lambda seconds: now.__setitem__(0, now[0] + seconds)), now

    def test_await_retries_until_complete_and_stable(self):
        timing, now = self.fake_clock()
        fetch = self.sequence(TraceUnavailable("not yet"), complete("normal")[:3], complete("normal"))
        self.assertEqual(await_trace(fetch, "t", "normal", PAYMENT, seconds=60, **timing), complete("normal"))
        self.assertEqual(now[0], 3 * 4)  # unavailable, partial, then three identical passing reads

    def test_late_orphan_after_a_passing_snapshot_fails(self):
        timing, _ = self.fake_clock()
        orphan = complete("normal") + [span("order", "stove.payment.v1 receive", "late", "absent")]
        with self.assertRaises(AssertionError) as failed:
            await_trace(self.sequence(complete("normal"), orphan), "t", "normal", PAYMENT, seconds=30, **timing)
        self.assertIn("spans without parent", str(failed.exception))

    def test_a_late_valid_span_restarts_the_stable_count_and_is_judged(self):
        timing, now = self.fake_clock()
        later = complete("normal") + [span("settlement", "stove.payment.v1 receive", "late", "s")]
        spans = await_trace(self.sequence(complete("normal"), complete("normal"), later), "t", "normal", PAYMENT, seconds=60, **timing)
        self.assertEqual(spans, later)
        self.assertEqual(now[0], 3 * 4)

    def test_a_trace_that_keeps_changing_fails_at_the_deadline(self):
        timing, _ = self.fake_clock()
        counter = [0]

        def growing(trace_id):
            counter[0] += 1
            return complete("normal") + [span("settlement", "extra", "e" + str(i), "s") for i in range(counter[0])]
        with self.assertRaises(AssertionError) as failed:
            await_trace(growing, "t", "normal", PAYMENT, seconds=30, **timing)
        self.assertIn("still changing", str(failed.exception))

    def test_parses_resource_spans_json_with_hex_ids_and_url_full(self):
        trace = {"resourceSpans": [{"resource": {"attributes": [{"key": "service.name", "value": {"stringValue": "payment"}}]},
                                    "scopeSpans": [{"spans": [{"name": "POST", "kind": 3, "spanId": "0102030405060708",
                                                               "parentSpanId": "0807060504030201",
                                                               "attributes": [{"key": "url.full", "value": {"stringValue": PG + "/cancel"}},
                                                                              {"key": "http.request.method", "value": {"stringValue": "POST"}}]}]}]}]}
        spans = parse_trace(trace)
        self.assertEqual((spans[0]["spanId"], spans[0]["parentSpanId"], spans[0]["method"]), ("0102030405060708", "0807060504030201", "POST"))
        self.assertEqual(pg_call(spans[0], PAYMENT), "cancel")

    def test_await_fails_at_the_deadline_with_the_last_problems(self):
        now = [0.0]

        def fetch(trace_id):
            raise TraceUnavailable("Tempo returned no spans for t")

        def sleep(seconds):
            now[0] += seconds
        with self.assertRaises(AssertionError) as failed:
            await_trace(fetch, "t", "normal", PAYMENT, seconds=9, interval=3, clock=lambda: now[0], sleep=sleep)
        self.assertIn("no spans", str(failed.exception))
        self.assertEqual(now[0], 9)

    def test_partial_trace_never_passes(self):
        now = [0.0]
        with self.assertRaises(AssertionError) as failed:
            await_trace(lambda trace_id: complete("lost-response")[:6], "t", "lost-response", PAYMENT, seconds=3,
                        clock=lambda: now[0], sleep=lambda s: now.__setitem__(0, now[0] + s))  # never passes, so never stable
        self.assertIn("no PG lookup span", str(failed.exception))

    def test_required_services_are_the_refund_flow(self):
        self.assertEqual(refund_tracing.REQUIRED_SERVICES, ("gateway", "order", "license", "payment", "settlement"))


if __name__ == "__main__":
    unittest.main()
