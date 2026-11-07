import json
import unittest
import urllib.request
import urllib.error
from support.portone_fake import PortOneFake


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
