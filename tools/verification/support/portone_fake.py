"""Thread-safe, loopback-only PortOne V2 journey fake. No external calls.

One paymentId keeps an ordered list of attempts (PortOne transactions). GET /payments/{id}
shows the latest attempt; GET /payments/{id}/transactions lists every attempt, so a FAILED
attempt stays in the history and the same paymentId can be retried with a new txId.

Console-only control API (decision 119), caller "console" only:
POST /_fake/control/payments/{paymentId}/{action}
  fail-attempt     latest READY attempt -> FAILED (payment window closed / PG auth failed)
  pay-pending      latest READY attempt -> PAY_PENDING without confirm (PG still deciding before our admission)
  arm-confirm      {"outcome": FAILED|PAY_PENDING|REJECT_400|REJECT_403} applied to the next valid confirm
  settle           {"status": PAID|FAILED} latest PAY_PENDING attempt
  console-approve  latest READY attempt -> PAID without confirm (approval without our admission)
  console-cancel   latest PAID attempt -> full CANCELLED (operator cancels in the PortOne console)
  arm-cancel       {"outcome": LOST_RESPONSE|PG_ERROR|FAILED, "failLookups": n} applied to the next valid cancel of a
                   PAID attempt. LOST_RESPONSE cancels, then closes the connection without an answer; PG_ERROR
                   answers HTTP 503 without cancelling and without recording an idempotent replay. The next n
                   GET /payments/{id} calls after that cancel answer 503 (refund tracing, decision 119)
                   FAILED answers HTTP 200 with a FAILED cancellation (id, full amount, requestedAt, no cancelledAt)
                   and keeps the attempt PAID with cancelled 0; the FAILED row stays in the payment's cancellations,
                   which is the confirmed failure Payment restores the license from (PortOneRefundCancellationGateway
                   records the receipt, PortOneRefundPaymentLookup.inspectFailure confirms it)

Every action targets one paymentId and its latest attempt only, so a journey can prepare many orders
up front, drive each into its state, wait once past the order deadline and then settle each one
(decision 120 follow-up). arm-confirm is one-shot and cleared when a new attempt starts. While an
attempt is PAY_PENDING, confirm answers 409 PAYMENT_NOT_READY and a new attempt is refused until settle.

Response shapes follow the PortOne adapter fixtures and the 2026-10-27~31 PortOne TEST runs.
Inferred, not observed in a real run: the confirm PG failure answer (HTTP 502, type PG_PROVIDER,
PortOne V2 PgProviderError), the SDK refusal of an already paid paymentId (ALREADY_PAID), the
failure object fields (reason/pgCode/pgMessage, PortOne V2 PaymentFailure) and the history order
(oldest first; Payment does not depend on it) and the FAILED cancellation answer and row of arm-cancel
FAILED (PortOne V2 FailedPaymentCancellation, shaped by the Payment adapters). Webhooks are not sent.
"""
import copy
import datetime
import http.server
import json
import secrets
import threading
import urllib.parse

CONFIRM_OUTCOMES = {'FAILED', 'PAY_PENDING', 'REJECT_400', 'REJECT_403'}
CANCEL_OUTCOMES = {'LOST_RESPONSE', 'PG_ERROR', 'FAILED'}
CONTROL_ACTIONS = {'fail-attempt', 'pay-pending', 'arm-confirm', 'arm-cancel', 'settle', 'console-approve', 'console-cancel'}


def timestamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00', 'Z')


class PortOneFake:
    def __init__(self, secret, store, channel):
        self.callers = dict(secret) if isinstance(secret, dict) else {"payment": secret}
        self.store, self.channel = store, channel
        self.lock = threading.RLock()
        self.payments, self.replays, self.requests = {}, {}, []
        self.server = None

    def start(self):
        owner = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                self.handle_request()

            def do_POST(self):
                self.handle_request()

            def handle_request(self):
                with owner.lock:
                    body = {}
                    caller = next((name for name, secret in owner.callers.items()
                                   if self.headers.get('Authorization') == 'PortOne ' + secret), None)
                    try:
                        length = int(self.headers.get('Content-Length', 0))
                        if length > 16384:
                            raise ValueError('body too large')
                        body = json.loads(self.rfile.read(length)) if length else {}
                        if not isinstance(body, dict):
                            body = {}
                            raise ValueError('object required')
                        if caller is None:
                            status, result = 401, {'type': 'UNAUTHORIZED'}
                        else:
                            status, result = owner.dispatch(self.command, self.path, body, self.headers.get('Idempotency-Key'), caller)
                    except (ValueError, KeyError, TypeError):
                        status, result = 400, {'type': 'INFORMATION_MISMATCH'}
                    entry = dict(method=self.command, path=self.path, status=status, caller=caller,
                                 idempotencyKey=self.headers.get('Idempotency-Key'))
                    if self.path.endswith('/cancel'):
                        entry.update(amount=body.get('amount'), reason=body.get('reason'),
                                     cancellationId=result.get('cancellation', {}).get('id'))
                    if self.path.startswith('/_fake/control/'):
                        entry.update(control={k: body[k] for k in ('outcome', 'status', 'failLookups') if k in body})
                    owner.requests.append(entry)
                    if status is None:
                        # Lost response: the cancellation happened, the caller never hears about it.
                        entry.update(status='LOST')
                        self.close_connection = True
                        return
                    raw = json.dumps(result).encode()
                    self.send_response(status)
                    self.send_header('Content-Type', 'application/json')
                    self.send_header('Content-Length', str(len(raw)))
                    self.end_headers()
                    self.wfile.write(raw)

        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.base_url = 'http://127.0.0.1:' + str(self.server.server_port)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        return self

    def stop(self):
        if self.server:
            self.server.shutdown()
            self.server.server_close()
            self.thread.join(timeout=5)
            self.server = None

    # --- views -------------------------------------------------------------------------
    def view(self, payment, attempt):
        """PortOne Payment object as seen through one attempt; tokens and armed outcomes stay private."""
        result = {k: copy.deepcopy(payment[k]) for k in ('id', 'storeId', 'version', 'currency', 'channel', 'customData')}
        result.update(transactionId=attempt['id'], status=attempt['status'], requestedAt=attempt['requestedAt'],
                      amount=copy.deepcopy(attempt['amount']))
        for key in ('pgTxId', 'paidAt', 'failedAt', 'failure', 'cancelledAt', 'cancellations'):
            if key in attempt:
                result[key] = copy.deepcopy(attempt[key])
        return result

    def view_history(self, payment_id):
        """Attempt views of one paymentId, oldest first (journey evidence; same as GET .../transactions)."""
        with self.lock:
            payment = self.payments[payment_id]
            return [self.view(payment, attempt) for attempt in payment['attempts']]

    @staticmethod
    def latest(payment):
        return payment['attempts'][-1]

    # --- transitions -------------------------------------------------------------------
    @staticmethod
    def pay(attempt):
        attempt.update(status='PAID', pgTxId='pg-' + secrets.token_hex(12), paidAt=timestamp())
        attempt['amount']['paid'] = attempt['amount']['total']

    @staticmethod
    def fail(attempt, reason):
        attempt.update(status='FAILED', failedAt=timestamp(),
                       failure=dict(reason=reason, pgCode='FAKE_FAILED', pgMessage=reason))
        attempt['amount']['paid'] = 0

    @staticmethod
    def cancel(attempt, reason):
        remaining = attempt['amount']['paid'] - attempt['amount']['cancelled']
        now = timestamp()
        cancellation = dict(status='SUCCEEDED', id='cancel-' + secrets.token_hex(12), totalAmount=remaining,
                            taxFreeAmount=0, vatAmount=remaining // 11, reason=reason, requestedAt=now, cancelledAt=now)
        attempt.update(status='CANCELLED', cancelledAt=now,
                       cancellations=[{k: cancellation[k] for k in ('status', 'id', 'totalAmount', 'cancelledAt')}])
        attempt['amount']['cancelled'] = remaining
        return cancellation

    @staticmethod
    def fail_cancel(attempt, reason):
        """A cancellation the PG refused: the purchase stays PAID with nothing cancelled, the FAILED row stays."""
        remaining = attempt['amount']['paid'] - attempt['amount']['cancelled']
        cancellation = dict(status='FAILED', id='cancel-' + secrets.token_hex(12), totalAmount=remaining,
                            taxFreeAmount=0, vatAmount=remaining // 11, reason=reason, requestedAt=timestamp())
        attempt.setdefault('cancellations', []).append(copy.deepcopy(cancellation))
        return cancellation

    # --- dispatch ----------------------------------------------------------------------
    def dispatch(self, method, target, body, key, caller=None):
        parsed = urllib.parse.urlsplit(target)
        path = parsed.path
        if method == 'GET' and path == '/_fake/requests':
            return 200, copy.deepcopy(self.requests)
        if method == 'POST' and path == '/_fake/sdk/request-payment':
            return self.request_payment(body)
        if path.startswith('/_fake/control/'):
            return self.control(method, path, body, caller)
        parts = path.strip('/').split('/')
        if len(parts) not in (2, 3) or parts[0] != 'payments' or parts[1] not in self.payments:
            return 404, {'type': 'PAYMENT_NOT_FOUND'}
        payment_id = parts[1]
        payment = self.payments[payment_id]
        replay_key = (payment_id, parts[-1], key)
        if method == 'POST' and replay_key in self.replays:
            previous_body, previous_result = self.replays[replay_key]
            if body != previous_body:
                return 409, {'type': 'IDEMPOTENCY_CONFLICT'}
            return copy.deepcopy(previous_result)
        store = urllib.parse.parse_qs(parsed.query).get('storeId', [None])[0] if method == 'GET' else body.get('storeId')
        if store != self.store:
            return 403, {'type': 'STORE_MISMATCH'}
        if method == 'GET':
            if len(parts) == 2 and payment.get('failLookups', 0) > 0:
                payment['failLookups'] -= 1
                return 503, {'type': 'UNKNOWN', 'message': 'fake lookup unavailable'}
            if len(parts) == 2:
                return 200, self.view(payment, self.latest(payment))
            if parts[2] == 'transactions':
                items = []
                for attempt in payment['attempts']:
                    item = self.view(payment, attempt)
                    item.update(id=attempt['id'], paymentId=payment_id)
                    items.append(item)
                return 200, {'items': items}
        action = parts[-1]
        if method != 'POST' or action not in {'confirm', 'cancel'}:
            return 404, {'type': 'NOT_FOUND'}
        if not key or not (key.startswith('"') and key.endswith('"')):
            return 400, {'type': 'INFORMATION_MISMATCH'}
        attempt = self.latest(payment)
        if action == 'confirm':
            if (body.get('paymentToken'), body.get('txId'), body.get('totalAmount'), body.get('currency'), body.get('isTest')) != (attempt['token'], attempt['id'], attempt['amount']['total'], 'KRW', True):
                return 400, {'type': 'INFORMATION_MISMATCH'}
            if attempt['status'] != 'READY':
                return 409, {'type': 'ALREADY_PAID' if attempt['status'] in {'PAID', 'CANCELLED'} else 'PAYMENT_NOT_READY'}
            outcome = payment.pop('armedConfirm', None)
            if outcome == 'REJECT_400':
                result = 400, {'type': 'INVALID_REQUEST', 'message': 'fake confirm rejection'}
            elif outcome == 'REJECT_403':
                result = 403, {'type': 'FORBIDDEN', 'message': 'fake confirm rejection'}
            elif outcome == 'FAILED':
                self.fail(attempt, 'fake PG declined at confirm')
                result = 502, {'type': 'PG_PROVIDER', 'message': 'fake PG declined at confirm',
                               'pgCode': 'FAKE_FAILED', 'pgMessage': 'fake PG declined at confirm'}
            elif outcome == 'PAY_PENDING':
                attempt['status'] = 'PAY_PENDING'
                result = 200, {'status': 'PAY_PENDING'}
            else:
                self.pay(attempt)
                result = 200, {'status': 'PAID'}
        else:
            remaining = attempt['amount']['paid'] - attempt['amount']['cancelled']
            if attempt['status'] != 'PAID' or set(body) != {'storeId', 'amount', 'currentCancellableAmount', 'reason'} or body['amount'] != remaining or remaining <= 0 or body['currentCancellableAmount'] != remaining:
                return 409, {'type': 'CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN'}
            outcome = payment.pop('armedCancel', None)
            if outcome is not None:
                payment['failLookups'] = outcome['failLookups']
            if outcome is not None and outcome['outcome'] == 'PG_ERROR':
                return 503, {'type': 'PG_PROVIDER', 'message': 'fake PG cancel unavailable'}
            if outcome is not None and outcome['outcome'] == 'FAILED':
                result = 200, {'cancellation': self.fail_cancel(attempt, body['reason'])}
                self.replays[replay_key] = copy.deepcopy((body, result))
                return result
            result = 200, {'cancellation': self.cancel(attempt, body['reason'])}
            if outcome is not None and outcome['outcome'] == 'LOST_RESPONSE':
                self.replays[replay_key] = copy.deepcopy((body, result))
                return None, result[1]
        self.replays[replay_key] = copy.deepcopy((body, result))
        return result

    def request_payment(self, body):
        if (body['storeId'], body['channelKey'], body['currency'], body['payMethod']) != (self.store, self.channel, 'CURRENCY_KRW', 'EASY_PAY'):
            return 400, {'type': 'INFORMATION_MISMATCH'}
        payment_id = body['paymentId']
        payment = self.payments.get(payment_id)
        if payment is not None and self.latest(payment)['status'] not in {'READY', 'FAILED'}:
            # A PAID/PAY_PENDING/CANCELLED paymentId cannot start another attempt.
            return 409, {'type': 'ALREADY_PAID'}
        if payment is None:
            payment = dict(id=payment_id, storeId=self.store, version='V2', currency='KRW',
                           channel=dict(type='TEST', key=self.channel, pgProvider='TOSSPAY_V2'),
                           customData=json.dumps(body['customData']), attempts=[])
        elif json.dumps(body['customData']) != payment['customData']:
            return 400, {'type': 'INFORMATION_MISMATCH'}
        attempt = dict(id='tx-' + secrets.token_hex(12), token=secrets.token_urlsafe(32), status='READY', requestedAt=timestamp(),
                       amount=dict(total=body['totalAmount'], paid=0, discount=0, cancelled=0))
        payment['attempts'].append(attempt)
        payment.pop('armedConfirm', None)
        self.payments[payment_id] = payment
        return 200, dict(txId=attempt['id'], paymentToken=attempt['token'])

    def control(self, method, path, body, caller):
        parts = path.strip('/').split('/')
        if method != 'POST' or len(parts) != 5 or parts[:3] != ['_fake', 'control', 'payments'] or parts[4] not in CONTROL_ACTIONS:
            return 404, {'type': 'NOT_FOUND'}
        if caller != 'console':
            return 403, {'type': 'FORBIDDEN'}
        payment = self.payments.get(parts[3])
        if payment is None:
            return 404, {'type': 'PAYMENT_NOT_FOUND'}
        action, attempt = parts[4], self.latest(payment)
        if action == 'arm-confirm':
            if body.get('outcome') not in CONFIRM_OUTCOMES:
                return 400, {'type': 'INVALID_REQUEST'}
            if attempt['status'] != 'READY':
                return 409, {'type': 'INVALID_STATE'}
            payment['armedConfirm'] = body['outcome']
        elif action == 'arm-cancel':
            fails = body.get('failLookups', 0)
            if body.get('outcome') not in CANCEL_OUTCOMES or not isinstance(fails, int) or isinstance(fails, bool) or not 0 <= fails <= 10:
                return 400, {'type': 'INVALID_REQUEST'}
            if attempt['status'] != 'PAID':
                return 409, {'type': 'INVALID_STATE'}
            payment['armedCancel'] = dict(outcome=body['outcome'], failLookups=fails)
        elif action == 'settle':
            if body.get('status') not in {'PAID', 'FAILED'}:
                return 400, {'type': 'INVALID_REQUEST'}
            if attempt['status'] != 'PAY_PENDING':
                return 409, {'type': 'INVALID_STATE'}
            if body['status'] == 'PAID':
                self.pay(attempt)
            else:
                self.fail(attempt, 'fake PG settled as failed')
        else:
            required = 'PAID' if action == 'console-cancel' else 'READY'
            if attempt['status'] != required:
                return 409, {'type': 'INVALID_STATE'}
            if action == 'fail-attempt':
                self.fail(attempt, 'fake payment window closed')
            elif action == 'pay-pending':
                attempt['status'] = 'PAY_PENDING'
            elif action == 'console-approve':
                self.pay(attempt)
            else:
                self.cancel(attempt, 'fake console cancel')
        return 200, self.view(payment, attempt)
