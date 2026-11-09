import base64
import hashlib
import hmac
import http.server
import json
import threading
import unittest
import urllib.request
import urllib.error
from support.portone_fake import PortOneFake, webhook_key, webhook_signature


class PortOneFakeTest(unittest.TestCase):
    def setUp(self):
        self.fake = PortOneFake('secret', 'store', 'channel').start()
        self.addCleanup(self.fake.stop)

    def call(self, method, path, body=None, key=None, secret='secret'):
        headers = {'Authorization': 'PortOne ' + secret, 'Content-Type': 'application/json'}
        if key:
            headers['Idempotency-Key'] = key
        request = urllib.request.Request(self.fake.base_url + path,
            data=json.dumps(body).encode() if body is not None else None, headers=headers, method=method)
        try:
            response = urllib.request.urlopen(request, timeout=3)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, json.load(response)

    def test_contract(self):
        checkout = dict(storeId='store', channelKey='channel', paymentId='payment', orderName='game',
                        totalAmount=10000, currency='CURRENCY_KRW', payMethod='EASY_PAY', customData={'orderNo': 'order'})
        status, sdk = self.call('POST', '/_fake/sdk/request-payment', checkout)
        self.assertEqual(status, 200)
        path = '/payments/payment?storeId=store'
        self.assertEqual(self.call('GET', path, secret='bad')[0], 401)
        self.assertEqual(self.call('GET', '/payments/missing?storeId=store')[0], 404)
        self.assertEqual(self.call('GET', '/payments/payment?storeId=wrong')[0], 403)
        ready = self.call('GET', path)[1]
        self.assertEqual((ready['status'], ready['transactionId'], ready['amount']['paid']), ('READY', sdk['txId'], 0))
        confirm = dict(storeId='store', paymentToken=sdk['paymentToken'], txId=sdk['txId'], currency='KRW', totalAmount=10000, isTest=True)
        self.assertEqual(self.call('POST', '/payments/payment/confirm', dict(confirm, totalAmount=1), '"bad"')[0], 400)
        approved = self.call('POST', '/payments/payment/confirm', confirm, '"approval"')
        self.assertEqual(approved[0], 200)
        self.assertEqual(self.call('POST', '/payments/payment/confirm', confirm, '"approval"'), approved)
        self.assertEqual(self.call('POST', '/payments/payment/confirm', confirm, '"other"')[0], 409)
        paid = self.call('GET', path)[1]
        self.assertEqual(paid['status'], 'PAID')
        self.assertEqual(set(paid['amount']), {'total', 'paid', 'discount', 'cancelled'})
        self.assertEqual(paid['channel'], dict(type='TEST', key='channel', pgProvider='TOSSPAY_V2'))
        self.assertEqual(json.loads(paid['customData']), {'orderNo': 'order'})
        history = self.call('GET', '/payments/payment/transactions?storeId=store')[1]['items']
        self.assertEqual(history[0]['paymentId'], 'payment')
        cancel = dict(storeId='store', amount=10000, currentCancellableAmount=10000, reason='ESD refund refund-id')
        self.assertEqual(self.call('POST', '/payments/payment/cancel', dict(cancel, currentCancellableAmount=1), '"bad-cancel"')[0], 409)
        cancelled = self.call('POST', '/payments/payment/cancel', cancel, '"cancel"')
        self.assertEqual(cancelled[0], 200)
        self.assertEqual(self.call('POST', '/payments/payment/cancel', cancel, '"cancel"'), cancelled)
        row = cancelled[1]['cancellation']
        self.assertEqual(set(row), {'status', 'id', 'totalAmount', 'taxFreeAmount', 'vatAmount', 'reason', 'requestedAt', 'cancelledAt'})
        self.assertEqual(row['reason'], cancel['reason'])
        after = self.call('GET', path)[1]
        self.assertEqual(after, self.call('GET', path)[1])
        self.assertEqual(after['status'], 'CANCELLED')
        self.assertEqual(after['cancellations'][0]['id'], row['id'])
        self.assertEqual((after['pgTxId'], after['paidAt']), (paid['pgTxId'], paid['paidAt']))
        log = self.call('GET', '/_fake/requests')[1]
        self.assertNotIn('secret', json.dumps(log))
        self.assertNotIn(sdk['paymentToken'], json.dumps(log))
        self.assertEqual(self.fake.server.server_address[0], '127.0.0.1')

    def test_authentication_required_even_for_control_endpoints(self):
        request = urllib.request.Request(self.fake.base_url + '/_fake/requests')
        with self.assertRaises(urllib.error.HTTPError) as raised:
            urllib.request.urlopen(request, timeout=3)
        self.assertEqual(raised.exception.code, 401)
        raised.exception.close()

    def test_sdk_rejects_wrong_channel(self):
        status, _ = self.call('POST', '/_fake/sdk/request-payment', dict(
            storeId='store', channelKey='wrong', currency='CURRENCY_KRW', payMethod='EASY_PAY'))
        self.assertEqual(status, 400)
        self.assertEqual(self.fake.payments, {})

    def test_caller_map_and_unknown_secret(self):
        fake = PortOneFake({'payment': 'pay-secret', 'settlement': 'settle-secret'}, 'store', 'channel').start()
        self.addCleanup(fake.stop)
        self.fake.stop()
        self.fake = fake
        self.assertEqual(self.call('GET', '/_fake/requests', secret='unknown')[0], 401)
        self.assertEqual(self.call('GET', '/_fake/requests', secret='pay-secret')[0], 200)
        self.assertEqual(self.call('GET', '/_fake/requests', secret='settle-secret')[0], 200)
        self.assertEqual([row['caller'] for row in fake.requests], [None, 'payment', 'settlement'])
        self.assertNotIn('pay-secret', json.dumps(fake.requests))
        self.assertNotIn('settle-secret', json.dumps(fake.requests))

    def test_malformed_cancel_is_logged(self):
        request = urllib.request.Request(self.fake.base_url + '/payments/missing/cancel', data=b'{',
            headers={'Authorization': 'PortOne secret'}, method='POST')
        with self.assertRaises(urllib.error.HTTPError) as raised:
            urllib.request.urlopen(request, timeout=3)
        self.assertEqual(raised.exception.code, 400)
        raised.exception.close()
        self.assertEqual(self.fake.requests[-1]['status'], 400)

    def test_idempotency_key_rejects_changed_confirm_and_cancel_body(self):
        checkout = dict(storeId='store', channelKey='channel', paymentId='payment', orderName='game',
                        totalAmount=10000, currency='CURRENCY_KRW', payMethod='EASY_PAY', customData={'orderNo': 'order'})
        _, sdk = self.call('POST', '/_fake/sdk/request-payment', checkout)
        confirm = dict(storeId='store', paymentToken=sdk['paymentToken'], txId=sdk['txId'],
                       currency='KRW', totalAmount=10000, isTest=True)
        approved = self.call('POST', '/payments/payment/confirm', confirm, '"approval"')
        conflict = (409, {'type': 'IDEMPOTENCY_CONFLICT'})
        self.assertEqual(self.call('POST', '/payments/payment/confirm', dict(confirm, totalAmount=1), '"approval"'), conflict)
        self.assertEqual(self.call('POST', '/payments/payment/confirm', dict(confirm, storeId='wrong'), '"approval"'), conflict)
        self.assertEqual(self.call('POST', '/payments/payment/confirm', confirm, '"approval"'), approved)
        cancel = dict(storeId='store', amount=10000, currentCancellableAmount=10000, reason='ESD refund refund-id')
        cancelled = self.call('POST', '/payments/payment/cancel', cancel, '"cancel"')
        self.assertEqual(self.call('POST', '/payments/payment/cancel', dict(cancel, reason='changed'), '"cancel"'), conflict)
        self.assertEqual(self.call('POST', '/payments/payment/cancel', cancel, '"cancel"'), cancelled)


# Fields PortOnePaymentAttemptLookup/PortOnePaymentApprovalGateway read from a transaction-history item.
HISTORY_FIELDS = {'id', 'paymentId', 'storeId', 'version', 'currency', 'channel', 'amount', 'customData', 'status', 'requestedAt'}


class PortOneFakeControlTest(unittest.TestCase):
    """Decision 119: FAILED attempts with retry, PAY_PENDING, PAID without confirm, console cancel, confirm rejection."""

    def setUp(self):
        self.fake = PortOneFake({'payment': 'secret', 'console': 'console-secret'}, 'store', 'channel').start()
        self.addCleanup(self.fake.stop)

    call = PortOneFakeTest.call

    def checkout(self, payment_id='payment'):
        body = dict(storeId='store', channelKey='channel', paymentId=payment_id, orderName='game',
                    totalAmount=10000, currency='CURRENCY_KRW', payMethod='EASY_PAY', customData={'orderNo': 'order'})
        return self.call('POST', '/_fake/sdk/request-payment', body)

    def confirm_body(self, sdk):
        return dict(storeId='store', paymentToken=sdk['paymentToken'], txId=sdk['txId'], currency='KRW', totalAmount=10000, isTest=True)

    def control(self, action, body=None, payment_id='payment', secret='console-secret'):
        return self.call('POST', '/_fake/control/payments/' + payment_id + '/' + action, body or {}, secret=secret)

    def current(self):
        return self.call('GET', '/payments/payment?storeId=store')[1]

    def history(self):
        return self.call('GET', '/payments/payment/transactions?storeId=store')[1]['items']

    def test_failed_attempt_stays_in_history_and_same_payment_id_retries_with_new_tx(self):
        first = self.checkout()[1]
        self.assertEqual(self.control('fail-attempt')[0], 200)
        failed = self.current()
        self.assertEqual((failed['status'], failed['transactionId']), ('FAILED', first['txId']))
        self.assertIsInstance(failed['failure'], dict)
        self.assertIn('failedAt', failed)
        self.assertEqual(self.call('POST', '/payments/payment/confirm', self.confirm_body(first), '"late"')[0], 409)
        status, second = self.checkout()
        self.assertEqual(status, 200)
        self.assertNotEqual(second['txId'], first['txId'])
        self.assertNotEqual(second['paymentToken'], first['paymentToken'])
        ready = self.current()
        self.assertEqual((ready['status'], ready['transactionId']), ('READY', second['txId']))
        self.assertNotIn('failure', ready)
        items = self.history()
        self.assertEqual([(i['id'], i['status']) for i in items], [(first['txId'], 'FAILED'), (second['txId'], 'READY')])
        for item in items:
            self.assertLessEqual(HISTORY_FIELDS, set(item))
            self.assertEqual((item['paymentId'], item['storeId'], item['version'], item['currency']), ('payment', 'store', 'V2', 'KRW'))
            self.assertEqual(json.loads(item['customData']), {'orderNo': 'order'})
        self.assertIsInstance(items[0]['failure'], dict)
        self.assertIn('failedAt', items[0])
        self.assertEqual(self.call('POST', '/payments/payment/confirm', self.confirm_body(first), '"old"')[0], 400)
        self.assertEqual(self.call('POST', '/payments/payment/confirm', self.confirm_body(second), '"second"')[0], 200)
        self.assertEqual([i['status'] for i in self.history()], ['FAILED', 'PAID'])
        self.assertEqual(self.current()['transactionId'], second['txId'])
        self.assertEqual(self.checkout(), (409, {'type': 'ALREADY_PAID'}))

    def test_unsent_approval_leaves_ready_attempt_and_same_payment_id_reopens(self):
        # Decision 120: the window authenticated an attempt but our approval request never arrived.
        first = self.checkout()[1]
        status, second = self.checkout()
        self.assertEqual(status, 200)
        self.assertNotEqual(second['txId'], first['txId'])
        self.assertEqual(self.current()['transactionId'], second['txId'])
        self.assertEqual(self.call('POST', '/payments/payment/confirm', self.confirm_body(first), '"stale"')[0], 400)
        self.assertEqual(self.call('POST', '/payments/payment/confirm', self.confirm_body(second), '"second"')[0], 200)
        items = self.history()
        self.assertEqual([(i['id'], i['status']) for i in items], [(first['txId'], 'READY'), (second['txId'], 'PAID')])
        self.assertNotIn('failure', items[0])

    def test_confirm_pg_failure_fails_the_attempt_with_a_provider_error(self):
        sdk = self.checkout()[1]
        self.assertEqual(self.control('arm-confirm', {'outcome': 'FAILED'})[0], 200)
        rejected = self.call('POST', '/payments/payment/confirm', self.confirm_body(sdk), '"approval"')
        self.assertEqual(rejected[0], 502)
        self.assertEqual(rejected[1]['type'], 'PG_PROVIDER')
        self.assertEqual(self.call('POST', '/payments/payment/confirm', self.confirm_body(sdk), '"approval"'), rejected)
        self.assertEqual(self.current()['status'], 'FAILED')
        self.assertEqual(self.history()[0]['status'], 'FAILED')
        retry = self.checkout()[1]
        self.assertEqual(self.call('POST', '/payments/payment/confirm', self.confirm_body(retry), '"retry"')[0], 200)
        self.assertEqual([i['status'] for i in self.history()], ['FAILED', 'PAID'])

    def test_pay_pending_settles_to_paid_or_failed(self):
        for payment_id, final in (('payment', 'PAID'), ('other', 'FAILED')):
            sdk = self.checkout(payment_id)[1]
            self.assertEqual(self.control('settle', {'status': 'PAID'}, payment_id)[0], 409)
            self.control('arm-confirm', {'outcome': 'PAY_PENDING'}, payment_id)
            self.assertEqual(self.call('POST', '/payments/' + payment_id + '/confirm', self.confirm_body(sdk), '"a"')[0], 200)
            pending = self.call('GET', '/payments/' + payment_id + '?storeId=store')[1]
            self.assertEqual(pending['status'], 'PAY_PENDING')
            self.assertEqual(self.checkout(payment_id)[0], 409)
            self.assertEqual(self.control('settle', {'status': final}, payment_id)[0], 200)
            settled = self.call('GET', '/payments/' + payment_id + '?storeId=store')[1]
            self.assertEqual(settled['status'], final)
            self.assertEqual('pgTxId' in settled and 'paidAt' in settled, final == 'PAID')
            self.assertEqual('failure' in settled, final == 'FAILED')
            self.assertEqual(settled['amount']['paid'], 10000 if final == 'PAID' else 0)
            self.assertEqual(self.control('settle', {'status': final}, payment_id)[0], 409)

    def test_pay_pending_without_confirm_blocks_confirm_and_retry_until_settled(self):
        # Decision 120 follow-up: PAY_PENDING before our admission, then expiry, then a late PAID or FAILED.
        sdk = self.checkout()[1]
        self.assertEqual(self.control('pay-pending')[0], 200)
        self.assertEqual(self.control('pay-pending')[0], 409)
        pending = self.current()
        self.assertEqual((pending['status'], pending['transactionId'], pending['amount']['paid']), ('PAY_PENDING', sdk['txId'], 0))
        self.assertEqual(self.history()[0]['status'], 'PAY_PENDING')
        self.assertEqual(self.call('POST', '/payments/payment/confirm', self.confirm_body(sdk), '"a"'), (409, {'type': 'PAYMENT_NOT_READY'}))
        self.assertEqual(self.checkout(), (409, {'type': 'ALREADY_PAID'}))
        self.assertEqual(self.control('settle', {'status': 'PAID'})[0], 200)
        self.assertEqual([i['status'] for i in self.history()], ['PAID'])
        self.assertEqual([r for r in self.fake.requests if r['path'].endswith('/confirm') and r['status'] == 200], [])

    def test_console_approval_pays_without_confirm_and_console_cancel_fully_cancels(self):
        sdk = self.checkout()[1]
        self.assertEqual(self.control('console-cancel')[0], 409)
        self.assertEqual(self.control('console-approve')[0], 200)
        paid = self.current()
        self.assertEqual((paid['status'], paid['transactionId'], paid['amount']['paid']), ('PAID', sdk['txId'], 10000))
        self.assertTrue(paid['pgTxId'] and paid['paidAt'])
        self.assertEqual(self.call('POST', '/payments/payment/confirm', self.confirm_body(sdk), '"late"'), (409, {'type': 'ALREADY_PAID'}))
        self.assertEqual(self.control('console-cancel')[0], 200)
        cancelled = self.current()
        self.assertEqual((cancelled['status'], cancelled['amount']['cancelled']), ('CANCELLED', 10000))
        self.assertEqual((cancelled['pgTxId'], cancelled['paidAt']), (paid['pgTxId'], paid['paidAt']))
        rows = cancelled['cancellations']
        self.assertEqual([(r['status'], r['totalAmount']) for r in rows], [('SUCCEEDED', 10000)])
        self.assertEqual(self.history()[0]['status'], 'CANCELLED')
        api_cancel = dict(storeId='store', amount=10000, currentCancellableAmount=10000, reason='ESD refund r')
        self.assertEqual(self.call('POST', '/payments/payment/cancel', api_cancel, '"c"')[0], 409)
        confirms = [r for r in self.fake.requests if r['path'].endswith('/confirm') and r['status'] == 200]
        self.assertEqual(confirms, [])

    def test_confirm_rejection_injection_is_one_shot_and_keeps_ready(self):
        sdk = self.checkout()[1]
        for outcome, status in (('REJECT_400', 400), ('REJECT_403', 403)):
            self.control('arm-confirm', {'outcome': outcome})
            self.assertEqual(self.call('POST', '/payments/payment/confirm', self.confirm_body(sdk), '"' + outcome + '"')[0], status)
            self.assertEqual(self.current()['status'], 'READY')
        self.assertEqual(self.call('POST', '/payments/payment/confirm', self.confirm_body(sdk), '"approval"')[0], 200)

    def test_control_is_console_only_and_validated(self):
        self.checkout()
        self.assertEqual(self.control('console-approve', secret='secret')[0], 403)
        self.assertEqual(self.control('console-approve', secret='unknown')[0], 401)
        self.assertEqual(self.control('console-approve', payment_id='missing')[0], 404)
        self.assertEqual(self.control('unknown')[0], 404)
        self.assertEqual(self.control('arm-confirm', {'outcome': 'MAYBE'})[0], 400)
        self.assertEqual(self.control('settle', {'status': 'CANCELLED'})[0], 400)
        self.assertEqual(self.current()['status'], 'READY')
        self.assertTrue(all(r['caller'] in {'console', 'payment', None} for r in self.fake.requests))
        self.assertNotIn('console-secret', json.dumps(self.fake.requests))


class PortOneFakeCancelFaultTest(unittest.TestCase):
    """Refund tracing journeys: a cancellation whose answer is lost, or a PG error before any cancellation."""

    setUp = PortOneFakeControlTest.setUp
    call = PortOneFakeTest.call
    checkout = PortOneFakeControlTest.checkout
    confirm_body = PortOneFakeControlTest.confirm_body
    control = PortOneFakeControlTest.control
    current = PortOneFakeControlTest.current
    cancel_body = dict(storeId='store', amount=10000, currentCancellableAmount=10000, reason='ESD refund r')

    def paid(self):
        sdk = self.checkout()[1]
        self.assertEqual(self.call('POST', '/payments/payment/confirm', self.confirm_body(sdk), '"approval"')[0], 200)

    def raw_cancel(self, key):
        request = urllib.request.Request(self.fake.base_url + '/payments/payment/cancel', data=json.dumps(self.cancel_body).encode(),
            headers={'Authorization': 'PortOne secret', 'Content-Type': 'application/json', 'Idempotency-Key': key}, method='POST')
        return urllib.request.urlopen(request, timeout=3)

    def test_arm_cancel_is_validated_and_requires_a_paid_attempt(self):
        self.checkout()
        self.assertEqual(self.control('arm-cancel', {'outcome': 'LOST_RESPONSE'})[0], 409)
        self.call('POST', '/payments/payment/confirm', self.confirm_body(self.checkout()[1]), '"approval"')
        self.assertEqual(self.control('arm-cancel', {'outcome': 'MAYBE'})[0], 400)
        self.assertEqual(self.control('arm-cancel', {'outcome': 'PG_ERROR', 'failLookups': 11})[0], 400)
        self.assertEqual(self.control('arm-cancel', {'outcome': 'PG_ERROR', 'failLookups': True})[0], 400)
        self.assertEqual(self.control('arm-cancel', {'outcome': 'PG_ERROR'}, secret='secret')[0], 403)
        self.assertEqual(self.current()['status'], 'PAID')

    def test_lost_response_cancels_closes_the_connection_and_fails_the_next_lookups(self):
        self.paid()
        self.assertEqual(self.control('arm-cancel', {'outcome': 'LOST_RESPONSE', 'failLookups': 1})[0], 200)
        with self.assertRaises(Exception) as lost:
            self.raw_cancel('"cancel"').read()
        self.assertNotIsInstance(lost.exception, urllib.error.HTTPError)
        self.assertEqual(self.call('GET', '/payments/payment?storeId=store')[0], 503)
        cancelled = self.current()
        self.assertEqual((cancelled['status'], cancelled['amount']['cancelled']), ('CANCELLED', 10000))
        replay = self.call('POST', '/payments/payment/cancel', self.cancel_body, '"cancel"')
        self.assertEqual((replay[0], replay[1]['cancellation']['id']), (200, cancelled['cancellations'][0]['id']))
        self.assertEqual([r['status'] for r in self.fake.requests if r['path'].endswith('/cancel')], ['LOST', 200])

    def test_pg_error_does_not_cancel_and_the_same_key_retries_successfully(self):
        self.paid()
        self.control('arm-cancel', {'outcome': 'PG_ERROR', 'failLookups': 0})
        self.assertEqual(self.call('POST', '/payments/payment/cancel', self.cancel_body, '"cancel"'),
                         (503, {'type': 'PG_PROVIDER', 'message': 'fake PG cancel unavailable'}))
        self.assertEqual(self.current()['status'], 'PAID')
        self.assertEqual(self.call('POST', '/payments/payment/cancel', self.cancel_body, '"cancel"')[0], 200)
        self.assertEqual(self.current()['status'], 'CANCELLED')

    def test_requested_cancel_stays_pending_until_the_pg_settles_it(self):
        # Decision 138: the PG accepted the cancel but has not finished it; Payment's refund lookup reads it as undecided.
        self.paid()
        self.assertEqual(self.control('settle-cancel')[0], 409)
        self.assertEqual(self.control('arm-cancel', {'outcome': 'REQUESTED'})[0], 200)
        status, answer = self.call('POST', '/payments/payment/cancel', self.cancel_body, '"cancel"')
        self.assertEqual((status, answer['cancellation']['status'], answer['cancellation']['totalAmount']), (200, 'REQUESTED', 10000))
        pending = self.current()
        self.assertEqual((pending['status'], pending['amount']['cancelled']), ('PAID', 0))
        self.assertEqual([(r['status'], r['id']) for r in pending['cancellations']], [('REQUESTED', answer['cancellation']['id'])])
        self.assertNotIn('pendingCancel', pending)
        self.assertEqual(self.call('POST', '/payments/payment/cancel', self.cancel_body, '"cancel"'), (status, answer))
        self.assertEqual(self.call('POST', '/payments/payment/cancel', self.cancel_body, '"other"')[0], 409)
        self.assertEqual(self.control('settle-cancel')[0], 200)
        settled = self.current()
        self.assertEqual((settled['status'], settled['amount']['cancelled']), ('CANCELLED', 10000))
        self.assertEqual([(r['status'], r['id']) for r in settled['cancellations']], [('SUCCEEDED', answer['cancellation']['id'])])
        self.assertEqual(self.control('settle-cancel')[0], 409)


class WebhookReceiver:
    """Loopback stand-in for Payment's webhook endpoint; records each request as delivered."""

    def __init__(self, status=200):
        received = []
        self.received = received

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
                received.append(dict(path=self.path, headers={k.lower(): v for k, v in self.headers.items()}, body=body))
                self.send_response(status)
                self.send_header('Content-Length', '0')
                self.end_headers()

        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.url = 'http://127.0.0.1:' + str(self.server.server_port) + '/api/v1/payments/webhooks/portone'
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def stop(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)


class PortOneFakeWebhookTest(unittest.TestCase):
    """Decision 138: signed Standard Webhooks messages on attempt status changes, per-payment delivery modes."""

    SECRET = 'whsec_' + base64.b64encode(b'k' * 32).decode()

    def setUp(self):
        self.fake = PortOneFake({'payment': 'secret', 'console': 'console-secret'}, 'store', 'channel').start()
        self.addCleanup(self.fake.stop)
        self.receiver = WebhookReceiver()
        self.addCleanup(self.receiver.stop)

    call = PortOneFakeTest.call
    checkout = PortOneFakeControlTest.checkout
    confirm_body = PortOneFakeControlTest.confirm_body
    control = PortOneFakeControlTest.control

    def verified(self, request):
        """Independent Standard Webhooks check, the same steps as Payment HmacPortOneWebhookVerifier."""
        headers = request['headers']
        key = base64.b64decode(self.SECRET[len('whsec_'):])
        signed = (headers['webhook-id'] + '.' + headers['webhook-timestamp'] + '.').encode() + request['body']
        expected = base64.b64encode(hmac.new(key, signed, hashlib.sha256).digest()).decode()
        return headers['webhook-signature'] == 'v1,' + expected

    def test_signature_matches_the_standard_webhooks_example(self):
        # Example from the Standard Webhooks specification (standard-webhooks/standard-webhooks README).
        key = webhook_key('whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw')
        self.assertEqual(webhook_signature(key, 'msg_p5jXN8AQM9LWM0D4loKWxJek', 1614265330, b'{"test": 2432232314}'),
                         'v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=')
        with self.assertRaises(ValueError):
            webhook_key('not-a-whsec-secret')

    def test_webhooks_are_off_until_a_journey_enables_them(self):
        self.checkout()
        self.assertEqual(self.control('console-approve')[0], 200)
        self.fake.flush_webhooks()
        self.assertEqual((self.receiver.received, self.fake.deliveries, self.fake.sender), ([], [], None))
        with self.assertRaises(ValueError):
            self.fake.enable_webhooks('https://webhook.invalid/api/v1/payments/webhooks/portone', self.SECRET)

    def test_every_status_change_is_one_signed_message_sent_straight_to_payment(self):
        self.fake.enable_webhooks(self.receiver.url, self.SECRET)
        status, sdk = self.checkout()
        self.assertEqual(self.call('POST', '/payments/payment/confirm', self.confirm_body(sdk), '"approval"')[0], 200)
        self.assertEqual(self.call('POST', '/payments/payment/confirm', self.confirm_body(sdk), '"approval"')[0], 200)  # replay: no change
        self.fake.flush_webhooks()
        bodies = [json.loads(r['body']) for r in self.receiver.received]
        self.assertEqual([b['type'] for b in bodies], ['Transaction.Ready', 'Transaction.Paid'])
        for request, body in zip(self.receiver.received, bodies):
            self.assertTrue(self.verified(request), request['headers'])
            self.assertEqual(request['path'], '/api/v1/payments/webhooks/portone')
            self.assertEqual(body['data'], {'storeId': 'store', 'paymentId': 'payment', 'transactionId': sdk['txId']})
            self.assertRegex(request['headers']['webhook-id'], r'^[A-Za-z0-9_-]{1,200}$')
            self.assertTrue(body['timestamp'].endswith('Z'))
        self.assertEqual([(d['type'], d['kind'], d['status']) for d in self.fake.webhooks_for('payment')],
                         [('Transaction.Ready', 'delivered', 200), ('Transaction.Paid', 'delivered', 200)])

    def test_hold_waits_for_release_and_mute_drops(self):
        self.fake.enable_webhooks(self.receiver.url, self.SECRET)
        self.checkout('held')
        self.checkout('muted')
        self.fake.flush_webhooks()
        self.fake.webhook_mode('held', 'hold')
        self.fake.webhook_mode('muted', 'mute')
        self.control('console-approve', payment_id='held')
        self.control('console-approve', payment_id='muted')
        self.fake.flush_webhooks()
        self.assertEqual([json.loads(r['body'])['type'] for r in self.receiver.received], ['Transaction.Ready'] * 2)
        released = self.fake.release_webhooks('held')
        self.assertEqual(len(released), 1)
        self.fake.flush_webhooks()
        last = json.loads(self.receiver.received[-1]['body'])
        self.assertEqual((last['type'], last['data']['paymentId']), ('Transaction.Paid', 'held'))
        self.assertEqual([(d['type'], d['kind']) for d in self.fake.webhooks_for('muted')],
                         [('Transaction.Ready', 'delivered'), ('Transaction.Paid', 'muted')])
        self.assertEqual(self.fake.release_webhooks('held'), [])
        with self.assertRaises(ValueError):
            self.fake.webhook_mode('held', 'later')

    def test_a_pending_cancel_sends_transaction_cancelled_only_when_the_pg_settles_it(self):
        self.fake.enable_webhooks(self.receiver.url, self.SECRET)
        sdk = self.checkout()[1]
        self.call('POST', '/payments/payment/confirm', self.confirm_body(sdk), '"approval"')
        self.control('arm-cancel', {'outcome': 'REQUESTED'})
        cancel = dict(storeId='store', amount=10000, currentCancellableAmount=10000, reason='ESD refund r')
        self.assertEqual(self.call('POST', '/payments/payment/cancel', cancel, '"cancel"')[0], 200)
        self.fake.flush_webhooks()
        self.assertEqual([json.loads(r['body'])['type'] for r in self.receiver.received], ['Transaction.Ready', 'Transaction.Paid'])
        self.control('settle-cancel')
        self.fake.flush_webhooks()
        self.assertEqual(json.loads(self.receiver.received[-1]['body'])['type'], 'Transaction.Cancelled')

    def test_redelivery_keeps_id_and_body_and_a_forged_signature_fails_verification(self):
        self.fake.enable_webhooks(self.receiver.url, self.SECRET)
        self.checkout()
        self.fake.flush_webhooks()
        first = self.receiver.received[0]
        self.fake.redeliver(first['headers']['webhook-id'])
        self.fake.deliver_forged(first['headers']['webhook-id'])
        self.fake.flush_webhooks()
        again, forged = self.receiver.received[1], self.receiver.received[2]
        self.assertEqual((again['headers']['webhook-id'], again['body']), (first['headers']['webhook-id'], first['body']))
        self.assertTrue(self.verified(again))
        self.assertEqual((forged['headers']['webhook-id'], forged['body']), (first['headers']['webhook-id'], first['body']))
        self.assertFalse(self.verified(forged))
        self.assertEqual([d['kind'] for d in self.fake.webhooks_for('payment')], ['delivered', 'redelivered', 'forged'])
        with self.assertRaises(KeyError):
            self.fake.redeliver('msg_unknown')
