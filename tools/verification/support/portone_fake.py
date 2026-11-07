"""Thread-safe, loopback-only PortOne V2 contract fake; no external calls."""
import copy
import datetime
import http.server
import json
import secrets
import threading
import urllib.parse


def timestamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00', 'Z')


class PortOneFake:
    def __init__(self, secret, store, channel):
        self.callers = dict(secret) if isinstance(secret, dict) else {"payment": secret}
        self.store, self.channel = store, channel
        self.lock = threading.RLock()
        self.payments, self.tokens, self.replays, self.requests = {}, {}, {}, []
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
                            status, result = owner.dispatch(self.command, self.path, body, self.headers.get('Idempotency-Key'))
                    except (ValueError, KeyError, TypeError):
                        status, result = 400, {'type': 'INFORMATION_MISMATCH'}
                    entry = dict(method=self.command, path=self.path, status=status, caller=caller,
                                 idempotencyKey=self.headers.get('Idempotency-Key'))
                    if self.path.endswith('/cancel'):
                        entry.update(amount=body.get('amount'), reason=body.get('reason'))
                    owner.requests.append(entry)
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

    def dispatch(self, method, target, body, key):
        parsed = urllib.parse.urlsplit(target)
        path = parsed.path
        if method == 'GET' and path == '/_fake/requests':
            return 200, copy.deepcopy(self.requests)
        if method == 'POST' and path == '/_fake/sdk/request-payment':
            if (body['storeId'], body['channelKey'], body['currency'], body['payMethod']) != (self.store, self.channel, 'CURRENCY_KRW', 'EASY_PAY'):
                return 400, {'type': 'INFORMATION_MISMATCH'}
            payment_id = body['paymentId']
            if payment_id in self.payments:
                return 409, {'type': 'ALREADY_EXISTS'}
            tx = 'tx-' + secrets.token_hex(12)
            token = secrets.token_urlsafe(32)
            self.tokens[payment_id] = token
            self.payments[payment_id] = dict(id=payment_id, storeId=self.store, version='V2', currency='KRW',
                channel=dict(type='TEST', key=self.channel, pgProvider='TOSSPAY_V2'), customData=json.dumps(body['customData']),
                amount=dict(total=body['totalAmount'], paid=0, discount=0, cancelled=0), transactionId=tx, status='READY', cancellations=[])
            return 200, dict(txId=tx, paymentToken=token)
        parts = path.strip('/').split('/')
        if len(parts) not in (2, 3) or parts[0] != 'payments' or parts[1] not in self.payments:
            return 404, {'type': 'PAYMENT_NOT_FOUND'}
        payment_id = parts[1]
        payment = self.payments[payment_id]
        store = urllib.parse.parse_qs(parsed.query).get('storeId', [None])[0] if method == 'GET' else body.get('storeId')
        if store != self.store:
            return 403, {'type': 'STORE_MISMATCH'}
        if method == 'GET':
            if len(parts) == 2:
                return 200, copy.deepcopy(payment)
            if parts[2] == 'transactions':
                item = copy.deepcopy(payment)
                item.update(id=payment['transactionId'], paymentId=payment_id)
                return 200, {'items': [item]}
        action = parts[-1]
        replay_key = (payment_id, action, key)
        if method != 'POST' or action not in {'confirm', 'cancel'}:
            return 404, {'type': 'NOT_FOUND'}
        if not key or not (key.startswith('"') and key.endswith('"')):
            return 400, {'type': 'INFORMATION_MISMATCH'}
        if replay_key in self.replays:
            return copy.deepcopy(self.replays[replay_key])
        if action == 'confirm':
            if (body.get('paymentToken'), body.get('txId'), body.get('totalAmount'), body.get('currency'), body.get('isTest')) != (self.tokens[payment_id], payment['transactionId'], payment['amount']['total'], 'KRW', True):
                return 400, {'type': 'INFORMATION_MISMATCH'}
            if payment['status'] != 'READY':
                return 409, {'type': 'ALREADY_PAID'}
            payment.update(status='PAID', pgTxId='pg-' + secrets.token_hex(12), paidAt=timestamp())
            payment['amount']['paid'] = payment['amount']['total']
            result = 200, {'status': 'PAID'}
        else:
            remaining = payment['amount']['paid'] - payment['amount']['cancelled']
            if set(body) != {'storeId', 'amount', 'currentCancellableAmount', 'reason'} or body['amount'] != remaining or remaining <= 0 or body['currentCancellableAmount'] != remaining:
                return 409, {'type': 'CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN'}
            now = timestamp()
            cancellation = dict(status='SUCCEEDED', id='cancel-' + secrets.token_hex(12), totalAmount=remaining,
                taxFreeAmount=0, vatAmount=remaining // 11, reason=body['reason'], requestedAt=now, cancelledAt=now)
            payment.update(status='CANCELLED', cancelledAt=now, cancellations=[{k: cancellation[k] for k in ('status', 'id', 'totalAmount', 'cancelledAt')}])
            payment['amount']['cancelled'] = remaining
            result = 200, {'cancellation': cancellation}
        self.replays[replay_key] = copy.deepcopy(result)
        return result
