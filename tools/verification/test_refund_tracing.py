import http.server
import json
import threading
import unittest

from support import refund_tracing
from support.refund_tracing import TraceUnavailable, await_trace, fetch_trace, trace_problems

PAYMENT = "pay-1"
PG = "http://127.0.0.1:1/payments/" + PAYMENT


def span(service, name, span_id, parent="", kind="SPAN_KIND_INTERNAL", url=None):
    return dict(service=service, name=name, kind=kind, spanId=span_id, parentSpanId=parent, url=url, start=0)


def client(span_id, parent, url):
    return span("payment", "http " + ("post" if url.endswith("/cancel") else "get"), span_id, parent, "SPAN_KIND_CLIENT", url)


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

    def test_await_retries_until_complete(self):
        answers = [TraceUnavailable("not yet"), complete("normal")[:3], complete("normal")]

        def fetch(trace_id):
            answer = answers.pop(0)
            if isinstance(answer, Exception):
                raise answer
            return answer
        self.assertEqual(await_trace(fetch, "t", "normal", PAYMENT, seconds=60, sleep=lambda _: None), complete("normal"))
        self.assertEqual(answers, [])

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
                        clock=lambda: now[0], sleep=lambda s: now.__setitem__(0, now[0] + s))
        self.assertIn("no PG lookup span", str(failed.exception))

    def test_required_services_are_the_refund_flow(self):
        self.assertEqual(refund_tracing.REQUIRED_SERVICES, ("gateway", "order", "license", "payment", "settlement"))


if __name__ == "__main__":
    unittest.main()
