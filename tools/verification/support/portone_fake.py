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
  arm-cancel       {"outcome": LOST_RESPONSE|PG_ERROR|REQUESTED, "failLookups": n} applied to the next valid cancel of a
                   PAID attempt. LOST_RESPONSE cancels, then closes the connection without an answer; PG_ERROR
                   answers HTTP 503 without cancelling and without recording an idempotent replay. REQUESTED answers
                   200 with a REQUESTED cancellation and leaves the attempt PAID until settle-cancel (PG cancel still
                   pending; decision 138). The next n GET /payments/{id} calls after that cancel answer 503
                   (refund tracing, decision 119)
  settle-cancel    latest PAID attempt with a REQUESTED cancellation -> that cancellation SUCCEEDED, attempt CANCELLED

Every action targets one paymentId and its latest attempt only, so a journey can prepare many orders
up front, drive each into its state, wait once past the order deadline and then settle each one
(decision 120 follow-up). arm-confirm is one-shot and cleared when a new attempt starts. While an
attempt is PAY_PENDING, confirm answers 409 PAYMENT_NOT_READY and a new attempt is refused until settle.

Response shapes follow the PortOne adapter fixtures and the 2026-10-27~31 PortOne TEST runs.
Inferred, not observed in a real run: the confirm PG failure answer (HTTP 502, type PG_PROVIDER,
PortOne V2 PgProviderError), the SDK refusal of an already paid paymentId (ALREADY_PAID), the
failure object fields (reason/pgCode/pgMessage, PortOne V2 PaymentFailure), the history order
(oldest first; Payment does not depend on it) and a pending cancellation shown as a PAID payment with a
REQUESTED cancellation row (Payment's refund lookup reads it as undecided).

Webhooks (decision 138) are off unless a journey calls enable_webhooks(url, secret). Then every attempt status
change (READY, PAY_PENDING, PAID, FAILED, CANCELLED) becomes one Standard Webhooks message
({"type": "Transaction.Paid", "timestamp", "data": {storeId, paymentId, transactionId}}, HMAC-SHA256 over
"id.timestamp.body" with the whsec_ key), delivered by one background thread straight to Payment, like the
2026-10-27 edge that bypassed the Gateway. Per paymentId a journey chooses webhook_mode: deliver (default),
hold (kept until release_webhooks) or mute (dropped). redeliver(messageId) resends the same id and body,
deliver_forged signs with another key. Every outcome is recorded in deliveries. Harness controls are Python
methods, not the console API: they model PortOne's delivery, not an operator action.
"""
import base64
import copy
import datetime
import hashlib
import hmac
import http.server
import json
import queue
import secrets
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

CONFIRM_OUTCOMES = {'FAILED', 'PAY_PENDING', 'REJECT_400', 'REJECT_403'}
CANCEL_OUTCOMES = {'LOST_RESPONSE', 'PG_ERROR', 'REQUESTED'}
CONTROL_ACTIONS = {'fail-attempt', 'pay-pending', 'arm-confirm', 'arm-cancel', 'settle', 'settle-cancel', 'console-approve', 'console-cancel'}
WEBHOOK_TYPES = {'READY': 'Transaction.Ready', 'PAY_PENDING': 'Transaction.PayPending', 'PAID': 'Transaction.Paid',
                 'FAILED': 'Transaction.Failed', 'CANCELLED': 'Transaction.Cancelled'}
WEBHOOK_MODES = {'deliver', 'hold', 'mute'}


def webhook_key(secret):
    """Standard Webhooks signing key: the base64 part after whsec_."""
    if not isinstance(secret, str) or not secret.startswith('whsec_'):
        raise ValueError('whsec_ secret required')
    return base64.b64decode(secret[len('whsec_'):], validate=True)


def webhook_signature(key, message_id, delivered_at, body):
    """v1 signature of "id.timestamp.body" (Standard Webhooks; Payment HmacPortOneWebhookVerifier)."""
    signed = (message_id + '.' + str(delivered_at) + '.').encode() + body
    return 'v1,' + base64.b64encode(hmac.new(key, signed, hashlib.sha256).digest()).decode()


def timestamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00', 'Z')


class PortOneFake:
    def __init__(self, secret, store, channel):
        self.callers = dict(secret) if isinstance(secret, dict) else {"payment": secret}
        self.store, self.channel = store, channel
        self.lock = threading.RLock()
        self.payments, self.replays, self.requests = {}, {}, []
        self.server = None
        self.webhook_url, self.webhook_signing_key = None, None
        self.messages, self.deliveries, self.notified, self.held = {}, [], {}, {}
        self.outbox, self.sender = queue.Queue(), None

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
                    owner.notify_changes()
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
        if self.sender:
            self.outbox.put(None)
            self.sender.join(timeout=15)
            self.sender = None

    # --- webhooks (decision 138) -------------------------------------------------------
    def enable_webhooks(self, url, secret):
        """Send a signed webhook for every later attempt status change to url (Payment, not the Gateway)."""
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme != 'http' or parsed.hostname != '127.0.0.1':
            raise ValueError('loopback webhook url required')
        with self.lock:
            self.webhook_url, self.webhook_signing_key = url, webhook_key(secret)
            for payment_id, payment in self.payments.items():
                for attempt in payment['attempts']:
                    self.notified[(payment_id, attempt['id'])] = attempt['status']
            if self.sender is None:
                self.sender = threading.Thread(target=self.send_loop, name='portone-fake-webhooks', daemon=True)
                self.sender.start()

    def webhook_mode(self, payment_id, mode):
        if mode not in WEBHOOK_MODES:
            raise ValueError('unknown webhook mode ' + str(mode))
        with self.lock:
            self.payments[payment_id]['webhookMode'] = mode

    def release_webhooks(self, payment_id):
        """Deliver this payment's held messages now, oldest first; later changes follow its current mode."""
        with self.lock:
            released = self.held.pop(payment_id, [])
            for message_id in released:
                self.outbox.put((message_id, self.webhook_signing_key, 'released'))
            return list(released)

    def redeliver(self, message_id):
        """PortOne-style retry: same id and body, new delivery timestamp and signature."""
        with self.lock:
            if message_id not in self.messages:
                raise KeyError(message_id)
            self.outbox.put((message_id, self.webhook_signing_key, 'redelivered'))

    def deliver_forged(self, message_id):
        """Same message signed with an unrelated key; Payment must answer 401 and store nothing."""
        with self.lock:
            if message_id not in self.messages:
                raise KeyError(message_id)
            self.outbox.put((message_id, secrets.token_bytes(32), 'forged'))

    def flush_webhooks(self, timeout=30):
        """Wait until every queued delivery has an outcome in deliveries."""
        deadline = time.monotonic() + timeout
        while self.outbox.unfinished_tasks:
            if time.monotonic() > deadline:
                raise TimeoutError('webhook deliveries still pending')
            time.sleep(0.05)

    def webhooks_for(self, payment_id):
        with self.lock:
            return [copy.deepcopy(d) for d in self.deliveries if d['paymentId'] == payment_id]

    def notify_changes(self):
        """Turn attempt status changes since the last request into messages (caller holds the lock)."""
        if self.webhook_url is None:
            return
        for payment_id, payment in self.payments.items():
            for attempt in payment['attempts']:
                identity = (payment_id, attempt['id'])
                if self.notified.get(identity) == attempt['status'] or attempt['status'] not in WEBHOOK_TYPES:
                    continue
                self.notified[identity] = attempt['status']
                message_id = 'msg_' + secrets.token_hex(12)
                body = json.dumps({'type': WEBHOOK_TYPES[attempt['status']], 'timestamp': timestamp(),
                                   'data': {'storeId': self.store, 'paymentId': payment_id,
                                            'transactionId': attempt['id']}}).encode()
                self.messages[message_id] = dict(paymentId=payment_id, type=WEBHOOK_TYPES[attempt['status']], body=body)
                mode = payment.get('webhookMode', 'deliver')
                if mode == 'deliver':
                    self.outbox.put((message_id, self.webhook_signing_key, 'delivered'))
                elif mode == 'hold':
                    self.held.setdefault(payment_id, []).append(message_id)
                else:
                    self.deliveries.append(dict(messageId=message_id, paymentId=payment_id,
                                                type=WEBHOOK_TYPES[attempt['status']], kind='muted', status=None))

    def send_loop(self):
        while True:
            item = self.outbox.get()
            try:
                if item is None:
                    return
                message_id, key, kind = item
                with self.lock:
                    message, url = self.messages[message_id], self.webhook_url
                delivered_at = int(time.time())
                request = urllib.request.Request(url, data=message['body'], method='POST', headers={
                    'Content-Type': 'application/json', 'webhook-id': message_id,
                    'webhook-timestamp': str(delivered_at),
                    'webhook-signature': webhook_signature(key, message_id, delivered_at, message['body'])})
                try:
                    with urllib.request.urlopen(request, timeout=10) as response:
                        status = response.status
                except urllib.error.HTTPError as error:
                    status = error.code
                except OSError as error:
                    status = 'ERROR ' + type(error).__name__
                with self.lock:
                    self.deliveries.append(dict(messageId=message_id, paymentId=message['paymentId'], type=message['type'],
                                                kind=kind, status=status))
            finally:
                self.outbox.task_done()

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
    def request_cancel(attempt, reason):
        """PG accepted the cancel but has not finished it: the attempt stays PAID with a REQUESTED row."""
        remaining = attempt['amount']['paid'] - attempt['amount']['cancelled']
        cancellation = dict(status='REQUESTED', id='cancel-' + secrets.token_hex(12), totalAmount=remaining,
                            taxFreeAmount=0, vatAmount=remaining // 11, reason=reason, requestedAt=timestamp())
        attempt['cancellations'] = [{k: cancellation[k] for k in ('status', 'id', 'totalAmount', 'requestedAt')}]
        attempt['pendingCancel'] = cancellation
        return cancellation

    @staticmethod
    def finish_cancel(attempt):
        pending = attempt.pop('pendingCancel')
        now = timestamp()
        attempt.update(status='CANCELLED', cancelledAt=now,
                       cancellations=[dict(status='SUCCEEDED', id=pending['id'], totalAmount=pending['totalAmount'], cancelledAt=now)])
        attempt['amount']['cancelled'] += pending['totalAmount']

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
            if attempt['status'] != 'PAID' or 'pendingCancel' in attempt or set(body) != {'storeId', 'amount', 'currentCancellableAmount', 'reason'} or body['amount'] != remaining or remaining <= 0 or body['currentCancellableAmount'] != remaining:
                return 409, {'type': 'CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN'}
            outcome = payment.pop('armedCancel', None)
            if outcome is not None:
                payment['failLookups'] = outcome['failLookups']
            if outcome is not None and outcome['outcome'] == 'PG_ERROR':
                return 503, {'type': 'PG_PROVIDER', 'message': 'fake PG cancel unavailable'}
            if outcome is not None and outcome['outcome'] == 'REQUESTED':
                result = 200, {'cancellation': self.request_cancel(attempt, body['reason'])}
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
        elif action == 'settle-cancel':
            if attempt['status'] != 'PAID' or 'pendingCancel' not in attempt:
                return 409, {'type': 'INVALID_STATE'}
            self.finish_cancel(attempt)
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
