"""Standalone PortOne test-channel probe; it does not change business app data."""

from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid


class ProbeError(RuntimeError):
    pass


@dataclass(frozen=True)
class Settings:
    store_id: str
    channel_key: str
    api_secret: str = field(repr=False)

    @classmethod
    def load(cls, path):
        values = {}
        for line in Path(path).read_text().splitlines():
            if line and not line.lstrip().startswith('#') and '=' in line:
                name, value = line.split('=', 1)
                values[name] = value.strip()
        required = ['PORTONE_STORE_ID', 'PORTONE_CHANNEL_KEY', 'PORTONE_API_SECRET']
        if any(not values.get(name) for name in required):
            raise ProbeError('MISSING_CONFIGURATION')
        return cls(*(values[name] for name in required))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class PortOneClient:
    def __init__(self, settings, opener=None):
        self.settings = settings
        self.opener = opener or urllib.request.build_opener(NoRedirect())

    def _request(self, method, path, body=None, attempt_id=None):
        headers = {'Authorization': 'PortOne ' + self.settings.api_secret,
                   'Accept': 'application/json'}
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers['Content-Type'] = 'application/json'
        if attempt_id is not None:
            headers['Idempotency-Key'] = json.dumps(attempt_id)
        request = urllib.request.Request('https://api.portone.io' + path,
                                         data=data, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=65) as response:
                payload = json.loads(response.read(1048576))
                if not isinstance(payload, dict):
                    raise ProbeError('INVALID_PG_RESPONSE')
                return payload
        except urllib.error.HTTPError as error:
            # Do not expose a provider error body, credentials, or customer data.
            code = 'PAYMENT_NOT_FOUND' if error.code == 404 and method == 'GET' else 'PG_HTTP_' + str(error.code)
            raise ProbeError(code) from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise ProbeError('PG_UNAVAILABLE') from None
        except ValueError:
            raise ProbeError('INVALID_PG_RESPONSE') from None

    def read_payment(self, payment_id):
        query = urllib.parse.urlencode({'storeId': self.settings.store_id})
        return self._request('GET', '/payments/' + urllib.parse.quote(payment_id, safe='') + '?' + query)

    def cancel_payment(self, payment_id, amount, attempt_id):
        return self._request('POST', '/payments/' + urllib.parse.quote(payment_id, safe='') + '/cancel',
                             {'storeId': self.settings.store_id, 'amount': amount,
                              'currentCancellableAmount': amount,
                              'reason': 'ESD 포트원 테스트 연동 검증'}, attempt_id)


class CheckoutProbe:
    def __init__(self, settings, run_dir, client):
        self.settings = settings
        self.run_dir = Path(run_dir)
        self.client = client
        self.run_dir.mkdir(parents=True, exist_ok=True)
        self.state_path = self.run_dir / 'state.json'
        if self.state_path.exists():
            self.state = json.loads(self.state_path.read_text())
            if (self.state.get('store_id') != settings.store_id
                    or self.state.get('channel_key') != settings.channel_key
                    or self.state.get('amount') != 9000):
                raise ProbeError('RUN_CONFIGURATION_MISMATCH')
        else:
            self.state = {'payment_id': 'esd-pg-check-' + str(uuid.uuid4()), 'amount': 9000,
                          'store_id': settings.store_id, 'channel_key': settings.channel_key,
                          'created_at': datetime.now(timezone.utc).isoformat()}
            self._save()

    def _save(self):
        with tempfile.NamedTemporaryFile(mode='w', dir=self.run_dir, delete=False) as output:
            json.dump(self.state, output, ensure_ascii=False, indent=2)
            output.write('\n')
            output.flush()
            os.fsync(output.fileno())
            temporary = output.name
        os.replace(temporary, self.state_path)

    def public_config(self):
        return {'payment': {'storeId': self.settings.store_id, 'channelKey': self.settings.channel_key,
                            'paymentId': self.state['payment_id'], 'orderName': 'ESD 테스트 상품',
                            'totalAmount': self.state['amount'], 'currency': 'CURRENCY_KRW',
                            'payMethod': 'EASY_PAY'}}

    def verify(self):
        try:
            payment = self.client.read_payment(self.state['payment_id'])
        except ProbeError as error:
            if str(error) == 'PAYMENT_NOT_FOUND' and not self.state.get('last_verified'):
                return {'status': 'NOT_CREATED', 'payment_id': self.state['payment_id']}
            raise
        if not isinstance(payment, dict):
            raise ProbeError('INVALID_PG_RESPONSE')
        channel = payment.get('channel', {})
        amount = payment.get('amount', {})
        if not isinstance(channel, dict) or not isinstance(amount, dict):
            raise ProbeError('INVALID_PG_RESPONSE')
        if (payment.get('id') != self.state['payment_id']
                or payment.get('storeId') != self.settings.store_id
                or payment.get('version') != 'V2' or payment.get('currency') != 'KRW'
                or channel.get('type') != 'TEST' or channel.get('key') != self.settings.channel_key
                or channel.get('pgProvider') != 'TOSSPAY_V2'
                or type(amount.get('total')) is not int or amount['total'] != self.state['amount']):
            raise ProbeError('PAYMENT_EVIDENCE_MISMATCH')
        status = payment.get('status')
        if status not in {'READY', 'PENDING', 'PAY_PENDING', 'FAILED', 'PAID', 'CANCELLED', 'PARTIAL_CANCELLED'}:
            raise ProbeError('UNSUPPORTED_PG_STATUS')
        if status in {'PAID', 'CANCELLED', 'PARTIAL_CANCELLED'}:
            if (type(amount.get('paid')) is not int or amount['paid'] != self.state['amount']
                    or type(amount.get('discount')) is not int or amount['discount'] != 0
                    or type(amount.get('cancelled')) is not int
                    or not isinstance(payment.get('transactionId'), str) or not payment['transactionId']):
                raise ProbeError('PAYMENT_EVIDENCE_MISMATCH')
        if status == 'PAID' and amount.get('cancelled') != 0:
            raise ProbeError('CANCELLATION_EVIDENCE_MISMATCH')
        if status == 'CANCELLED':
            cancellations = payment.get('cancellations')
            if not isinstance(cancellations, list) or amount.get('cancelled') != self.state['amount']:
                raise ProbeError('CANCELLATION_EVIDENCE_MISMATCH')
            succeeded = [item for item in cancellations if isinstance(item, dict) and item.get('status') == 'SUCCEEDED']
            if (not succeeded or any(type(item.get('totalAmount')) is not int or not item.get('id') for item in succeeded)
                    or sum(item['totalAmount'] for item in succeeded) != self.state['amount']):
                raise ProbeError('CANCELLATION_EVIDENCE_MISMATCH')
        result = {'payment_id': self.state['payment_id'], 'status': status,
                  'amount': amount['total'], 'cancelled_amount': amount.get('cancelled', 0),
                  'transaction_id': payment.get('transactionId'), 'channel_type': channel['type'],
                  'pg_provider': channel['pgProvider'],
                  'cancellation_unconfirmed': bool(self.state.get('cancel_attempt_id')) and status != 'CANCELLED'}
        self.state['last_verified'] = result
        self.state['last_checked_at'] = datetime.now(timezone.utc).isoformat()
        self._save()
        return result

    def cancel(self):
        current = self.verify()
        if current['status'] == 'CANCELLED':
            return current
        if current['status'] != 'PAID':
            raise ProbeError('VERIFIED_FULL_PAYMENT_REQUIRED')
        now = datetime.now(timezone.utc)
        if self.state.get('cancel_attempt_id'):
            try:
                requested_at = datetime.fromisoformat(self.state['cancel_requested_at'])
                if now - requested_at >= timedelta(hours=3):
                    raise ProbeError('IDEMPOTENCY_WINDOW_EXPIRED_LOOKUP_REQUIRED')
            except (ValueError, KeyError, TypeError):
                raise ProbeError('INVALID_LOCAL_ATTEMPT_STATE') from None
        else:
            self.state['cancel_attempt_id'] = str(uuid.uuid4())
            self.state['cancel_requested_at'] = now.isoformat()
            self._save()
        self.client.cancel_payment(self.state['payment_id'], self.state['amount'], self.state['cancel_attempt_id'])
        return self.verify()
