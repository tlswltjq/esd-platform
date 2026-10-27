import copy
import io
import importlib.util
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
import urllib.error
import urllib.parse

from support.portone_checkout import CheckoutProbe, PortOneClient, ProbeError, Settings


class FakeClient:
    def __init__(self):
        self.payment = None
        self.calls = []
        self.failure = False
        self.before_cancel = lambda attempt: None

    def read_payment(self, payment_id):
        if self.payment is None:
            raise ProbeError("PAYMENT_NOT_FOUND")
        return copy.deepcopy(self.payment)

    def cancel_payment(self, payment_id, amount, attempt_id):
        self.before_cancel(attempt_id)
        self.calls.append((payment_id, amount, attempt_id))
        if self.failure:
            raise ProbeError("PG_UNAVAILABLE")
        self.payment['status'] = 'CANCELLED'
        self.payment['amount']['cancelled'] = amount
        self.payment['cancellations'] = [
            {'status': 'SUCCEEDED', 'id': 'cancel-test', 'totalAmount': amount}
        ]


class CheckoutProbeTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name)
        self.settings = Settings('store-test', 'channel-key-test', 'secret-never-public')
        self.client = FakeClient()
        self.probe = CheckoutProbe(self.settings, self.path, self.client)

    def paid(self):
        payment_id = self.probe.public_config()['payment']['paymentId']
        self.client.payment = {
            'id': payment_id, 'storeId': self.settings.store_id, 'version': 'V2',
            'status': 'PAID', 'currency': 'KRW', 'transactionId': 'transaction-test',
            'channel': {'type': 'TEST', 'key': self.settings.channel_key,
                        'pgProvider': 'TOSSPAY_V2'},
            'amount': {'total': 9000, 'paid': 9000, 'discount': 0, 'cancelled': 0},
        }

    def test_public_config_uses_prepared_channel_and_never_contains_server_secret(self):
        config = self.probe.public_config()
        self.assertEqual(config['payment']['storeId'], self.settings.store_id)
        self.assertEqual(config['payment']['channelKey'], self.settings.channel_key)
        self.assertEqual(config['payment']['payMethod'], 'EASY_PAY')
        self.assertEqual(config['payment']['totalAmount'], 9000)
        self.assertNotIn(self.settings.api_secret, json.dumps(config))
        self.assertNotIn(self.settings.api_secret, repr(self.settings))
        resumed = CheckoutProbe(self.settings, self.path, self.client)
        self.assertEqual(resumed.public_config()['payment'], config['payment'])

    def test_no_pg_record_is_not_a_confirmed_payment(self):
        self.assertEqual(self.probe.verify()['status'], 'NOT_CREATED')
        self.assertEqual(self.client.calls, [])

    def test_paid_is_verified_from_server_and_evidence_excludes_secret(self):
        self.paid()
        self.assertEqual(self.probe.verify()['status'], 'PAID')
        saved = json.loads((self.path / 'state.json').read_text())
        self.assertEqual(saved['last_verified']['status'], 'PAID')
        self.assertNotIn(self.settings.api_secret, json.dumps(saved))

    def test_mismatched_purchase_or_live_channel_cannot_be_verified_or_cancelled(self):
        mutations = [
            lambda p: p.update(id='another-payment'),
            lambda p: p.update(storeId='another-store'),
            lambda p: p.update(currency='USD'),
            lambda p: p.update(version='V1'),
            lambda p: p['channel'].update(type='LIVE'),
            lambda p: p['channel'].update(key='another-channel'),
            lambda p: p['channel'].update(pgProvider='TOSSPAYMENTS'),
            lambda p: p['amount'].update(total=8999),
            lambda p: p['amount'].update(paid=8999),
            lambda p: p['amount'].update(discount=1000),
            lambda p: p['amount'].update(total=True),
        ]
        for mutation in mutations:
            with self.subTest(mutation=mutations.index(mutation)):
                self.paid()
                mutation(self.client.payment)
                with self.assertRaises(ProbeError):
                    self.probe.cancel()
                self.assertEqual(self.client.calls, [])

    def test_cancellation_key_is_saved_before_sending_and_reused_after_uncertainty(self):
        self.paid()
        self.client.failure = True

        def verify_saved(attempt):
            saved = json.loads((self.path / 'state.json').read_text())
            self.assertEqual(saved['cancel_attempt_id'], attempt)

        self.client.before_cancel = verify_saved
        with self.assertRaises(ProbeError):
            self.probe.cancel()
        first_attempt = self.client.calls[0][2]
        resumed = CheckoutProbe(self.settings, self.path, self.client)
        self.client.failure = False
        self.assertEqual(resumed.cancel()['status'], 'CANCELLED')
        self.assertEqual(self.client.calls[1][2], first_attempt)

    def test_full_cancellation_is_requeried_and_duplicate_does_not_send_again(self):
        self.paid()
        result = self.probe.cancel()
        self.assertEqual(result['status'], 'CANCELLED')
        self.assertEqual(result['cancelled_amount'], 9000)
        self.assertEqual(self.probe.cancel()['status'], 'CANCELLED')
        self.assertEqual(len(self.client.calls), 1)

    def test_accepted_request_without_confirmed_cancellation_stays_unconfirmed(self):
        self.paid()

        def requested(payment_id, amount, attempt_id):
            self.client.calls.append((payment_id, amount, attempt_id))
            return {'cancellation': {'status': 'REQUESTED'}}

        self.client.cancel_payment = requested
        result = self.probe.cancel()
        self.assertEqual(result['status'], 'PAID')
        self.assertTrue(result['cancellation_unconfirmed'])
        self.assertTrue(self.probe.verify()['cancellation_unconfirmed'])

    def test_cancelled_status_without_matching_successful_amount_is_rejected(self):
        self.paid()
        self.client.payment.update(status='CANCELLED', cancellations=[])
        self.client.payment['amount']['cancelled'] = 9000
        with self.assertRaises(ProbeError):
            self.probe.verify()

    def test_partial_cancellation_cannot_trigger_a_full_second_cancellation(self):
        self.paid()
        self.client.payment['status'] = 'PARTIAL_CANCELLED'
        self.client.payment['amount']['cancelled'] = 1000
        with self.assertRaises(ProbeError):
            self.probe.cancel()
        self.assertEqual(self.client.calls, [])

    def test_expired_pg_idempotency_window_requires_lookup_and_does_not_create_new_key(self):
        self.paid()
        self.client.failure = True
        with self.assertRaises(ProbeError):
            self.probe.cancel()
        state_path = self.path / 'state.json'
        state = json.loads(state_path.read_text())
        state['cancel_requested_at'] = (datetime.now(timezone.utc) - timedelta(hours=4)).isoformat()
        state_path.write_text(json.dumps(state))
        resumed = CheckoutProbe(self.settings, self.path, self.client)
        with self.assertRaisesRegex(ProbeError, 'LOOKUP_REQUIRED'):
            resumed.cancel()
        self.assertEqual(len(self.client.calls), 1)


class PortOneClientTest(unittest.TestCase):
    def setUp(self):
        self.settings = Settings('store-test', 'channel-key-test', 'secret-never-public')

    def test_get_uses_store_scope_and_secret_only_in_authorization(self):
        calls = []

        class Opener:
            def open(self, request, timeout):
                calls.append((request, timeout))
                return io.BytesIO(b'{"status":"PAID"}')

        client = PortOneClient(self.settings, Opener())
        self.assertEqual(client.read_payment('payment-test')['status'], 'PAID')
        request, timeout = calls[0]
        self.assertEqual(request.get_method(), 'GET')
        self.assertEqual(request.get_header('Authorization'), 'PortOne ' + self.settings.api_secret)
        self.assertEqual(urllib.parse.parse_qs(urllib.parse.urlsplit(request.full_url).query), {'storeId': ['store-test']})
        self.assertTrue(request.full_url.startswith('https://api.portone.io/payments/'))
        self.assertNotIn(self.settings.api_secret, request.full_url)
        self.assertGreaterEqual(timeout, 60)

    def test_cancellation_uses_quoted_stable_key_and_explicit_full_amount_guard(self):
        calls = []

        class Opener:
            def open(self, request, timeout):
                calls.append(request)
                return io.BytesIO(b'{"cancellation":{"status":"REQUESTED"}}')

        client = PortOneClient(self.settings, Opener())
        client.cancel_payment('payment-test', 9000, 'attempt-test')
        request = calls[0]
        self.assertEqual(request.get_method(), 'POST')
        self.assertEqual(request.get_header('Idempotency-key'), '"attempt-test"')
        body = json.loads(request.data)
        self.assertEqual(body['storeId'], 'store-test')
        self.assertEqual(body['amount'], 9000)
        self.assertEqual(body['currentCancellableAmount'], 9000)
        self.assertNotIn(self.settings.api_secret, json.dumps(body))

    def test_http_failure_body_cannot_expose_secret(self):
        sensitive = self.settings.api_secret

        class Opener:
            def open(self, request, timeout):
                raise urllib.error.HTTPError(request.full_url, 401, sensitive, {}, io.BytesIO(sensitive.encode()))

        with self.assertRaises(ProbeError) as result:
            PortOneClient(self.settings, Opener()).read_payment('payment-test')
        self.assertEqual(str(result.exception), 'PG_HTTP_401')
        self.assertNotIn(sensitive, str(result.exception))

    def test_invalid_success_body_is_not_accepted(self):
        class Opener:
            def open(self, request, timeout):
                return io.BytesIO(b'[]')

        with self.assertRaisesRegex(ProbeError, 'INVALID_PG_RESPONSE'):
            PortOneClient(self.settings, Opener()).read_payment('payment-test')


class LocalHandlerTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.settings = Settings('store-test', 'channel-key-test', 'secret-never-public')
        self.client = FakeClient()
        self.probe = CheckoutProbe(self.settings, Path(self.directory.name), self.client)
        spec = importlib.util.spec_from_file_location('portone_probe_server', Path(__file__).parent / 'portone-test-checkout.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.handler = module.make_handler(self.probe, 'local-test-token')

    def request(self, method, path, host='127.0.0.1:18792', origin='http://127.0.0.1:18792',
                token='local-test-token', body='{}'):
        # Exercise the real HTTP parser/handler using memory streams, without sockets or browser access.
        handler = self.handler.__new__(self.handler)
        handler.server = SimpleNamespace(server_port=18792)
        handler.client_address = ('127.0.0.1', 1)
        headers = [f'{method} {path} HTTP/1.1', 'Host: ' + host, 'Origin: ' + origin,
                   'X-Verification-Token: ' + token, 'Content-Type: application/json',
                   'Content-Length: ' + str(len(body.encode())), 'Connection: close', '', body]
        handler.rfile = io.BytesIO('\r\n'.join(headers).encode())
        handler.wfile = io.BytesIO()
        handler.close_connection = True
        handler.handle_one_request()
        raw = handler.wfile.getvalue()
        head, data = raw.split(b'\r\n\r\n', 1)
        status = int(head.split(b' ', 2)[1])
        return status, data

    def test_config_exposes_only_public_payment_settings_and_local_session_token(self):
        status, body = self.request('GET', '/api/config')
        self.assertEqual(status, 200)
        self.assertNotIn(self.settings.api_secret.encode(), body)
        self.assertEqual(json.loads(body)['token'], 'local-test-token')
        self.assertIn(b'"payMethod": "EASY_PAY"', body)

    def test_foreign_host_is_denied(self):
        status, _ = self.request('GET', '/api/config', host='attacker.example:18792')
        self.assertEqual(status, 403)

    def test_cross_origin_or_wrong_token_cannot_request_cancellation(self):
        for headers in [{'origin': 'https://attacker.example'}, {'token': 'wrong-token'}]:
            with self.subTest(headers=headers):
                status, _ = self.request('POST', '/api/cancel', **headers)
                self.assertEqual(status, 403)
                self.assertEqual(self.client.calls, [])

    def test_body_cannot_select_another_payment_id_or_amount(self):
        status, _ = self.request('POST', '/api/cancel', body='{"paymentId":"another-payment","amount":1000}')
        self.assertEqual(status, 400)
        self.assertEqual(self.client.calls, [])

    def test_get_cannot_execute_cancellation(self):
        status, _ = self.request('GET', '/api/cancel')
        self.assertEqual(status, 404)
        self.assertEqual(self.client.calls, [])

    def test_verify_uses_generated_payment_and_keeps_missing_record_unconfirmed(self):
        status, body = self.request('POST', '/api/verify')
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)['status'], 'NOT_CREATED')
        self.assertEqual(self.client.calls, [])


if __name__ == '__main__':
    unittest.main()
