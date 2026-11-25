"""Real Auth/Gateway role boundaries with isolated downstream HTTP fixtures."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading
from urllib.parse import parse_qs, urlsplit

import requests


# Expected permissions are declared independently of Gateway's matchers.
# Each tuple is method, external path, destination, required roles.
CASES = [
    ('GET', '/api/v1/storefront/products', 'store', set()),
    ('GET', '/api/v1/products/1', 'catalog', set()),
    ('GET', '/api/v1/studio/assets/1/asset', 'studio', set()),
    ('GET', '/api/v1/orders', 'order', {'MEMBER'}),
    ('POST', '/api/v1/orders', 'order', {'MEMBER'}),
    ('GET', '/api/v1/orders/order-1/refund', 'order', {'MEMBER'}),
    ('POST', '/api/v1/orders/order-1/refund/retries', 'order', {'MEMBER'}),
    ('POST', '/api/v1/orders/order-1/refund/reconsiderations', 'order', {'MEMBER'}),
    ('GET', '/api/v1/payments/order-1/checkout', 'payment', {'MEMBER'}),
    ('POST', '/api/v1/payments/order-1/approvals', 'payment', {'MEMBER'}),
    ('GET', '/api/v1/payments/order-1/attempt-failures', 'payment', {'MEMBER'}),
    ('GET', '/api/v1/library', 'license', {'MEMBER'}),
    ('POST', '/api/v1/downloads/1', 'download', {'MEMBER'}),
    ('GET', '/api/v1/studio/projects', 'studio', {'CREATOR'}),
    ('POST', '/api/v1/studio/projects/1/review-requests', 'studio', {'CREATOR'}),
    ('POST', '/api/v1/studio/projects/1/release-requests', 'studio', {'CREATOR'}),
    ('GET', '/api/v1/products/by-code/game/sales', 'catalog', {'CREATOR'}),
    ('PUT', '/api/v1/products/by-code/game/sales', 'catalog', {'CREATOR'}),
    ('PUT', '/api/v1/products/by-code/game/listing', 'catalog', {'CREATOR'}),
    ('GET', '/api/v1/promotions/seller/products/1', 'catalog', {'CREATOR'}),
    ('GET', '/api/v1/settlements/me/sales', 'settlement', {'CREATOR'}),
    ('GET', '/api/v1/settlements/me/refunds', 'settlement', {'CREATOR'}),
    ('GET', '/api/v1/reviews', 'review', {'REVIEWER', 'ADMIN'}),
    ('POST', '/api/v1/reviews/1/approve', 'review', {'REVIEWER', 'ADMIN'}),
    ('POST', '/api/v1/reviews/1/reject', 'review', {'REVIEWER', 'ADMIN'}),
    ('GET', '/api/v1/admin/catalog-release-jobs', 'catalog', {'ADMIN'}),
    ('GET', '/api/v1/admin/catalog-release-jobs/1', 'catalog', {'ADMIN'}),
    ('PUT', '/api/v1/admin/products/game/sales/restriction', 'catalog', {'ADMIN'}),
    ('PUT', '/api/v1/admin/products/game/downloads/restriction', 'catalog', {'ADMIN'}),
    ('PUT', '/api/v1/admin/products/game/listing/restriction', 'catalog', {'ADMIN'}),
    ('GET', '/api/v1/promotions/platform/products/1', 'catalog', {'ADMIN'}),
    ('GET', '/api/v1/settlements/sales', 'settlement', {'ADMIN'}),
    ('POST', '/api/v1/settlements/sellers/seller/months/2026-11/close', 'settlement', {'ADMIN'}),
    ('GET', '/api/v1/admin/refunds', 'order', {'ADMIN'}),
    ('POST', '/api/v1/admin/refunds/1/decision', 'order', {'ADMIN'}),
    ('POST', '/api/v1/admin/refunds/1/approval-stops', 'order', {'ADMIN'}),
    ('GET', '/api/v1/admin/refunds/1/reviews', 'order', {'ADMIN'}),
    ('POST', '/api/v1/admin/refunds/1/reconsiderations/1/decision', 'order', {'ADMIN'}),
    ('GET', '/api/v1/admin/refund-cancellation-holds', 'payment', {'ADMIN'}),
    ('POST', '/api/v1/admin/refund-cancellation-holds/1/resume', 'payment', {'ADMIN'}),
    ('GET', '/api/v1/admin/refund-delays', 'payment', {'ADMIN'}),
    ('GET', '/api/v1/admin/refund-delays/1/investigations', 'payment', {'ADMIN'}),
    ('POST', '/api/v1/admin/refund-delays/1/investigations', 'payment', {'ADMIN'}),
]


class Backends:
    def __init__(self):
        self.servers = []
        self.calls = []
        self.environment = {}
        for name in sorted({case[2] for case in CASES}):
            server = ThreadingHTTPServer(('127.0.0.1', 0), self.handler(name))
            self.servers.append(server)
            self.environment[name.upper() + '_URI'] = 'http://127.0.0.1:' + str(server.server_port)
            threading.Thread(target=server.serve_forever, daemon=True).start()

    def handler(self, backend):
        calls = self.calls

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def handle_request(self):
                payload = self.rfile.read(int(self.headers.get('Content-Length', '0')))
                calls.append({'backend': backend, 'method': self.command, 'path': self.path,
                              'authorization': self.headers.get('Authorization'),
                              'member': self.headers.get('X-Member-Id'), 'body': payload})
                status = int(parse_qs(urlsplit(self.path).query).get('status', ['200'])[0])
                body = json.dumps({'backend': backend, 'state': 'PENDING' if status == 202 else 'FIXTURE',
                                   'code': 'FIXTURE_ERROR' if status >= 400 else 'OK'}).encode()
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Cache-Control', 'no-store')
                self.send_header('X-Journey-Backend', backend)
                if status == 202:
                    self.send_header('Location', '/api/v1/payments/order-1/approvals/request-1')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            do_GET = handle_request
            do_POST = handle_request
            do_PUT = handle_request
            do_DELETE = handle_request

        return Handler

    def close(self):
        for server in self.servers:
            server.shutdown()
            server.server_close()


def verify(base, login, require, fixtures, password):
    """login(email, role) returns (BffClient, subject, Redis token key, stored tokens)."""
    actors = {}
    for label, signup, expected in [
        ('member', '/api/v1/auth/signup/member', {'MEMBER'}),
        ('creator', '/api/v1/auth/signup', {'MEMBER', 'CREATOR'}),
        ('reviewer', None, {'REVIEWER'}),
        ('admin', None, {'ADMIN', 'REVIEWER'}),
    ]:
        email = label + '@journey.example'
        if signup:
            require(requests.post(base + signup, json={'email': email, 'password': password},
                                  timeout=10).status_code == 200, label + ' registration')
        client, subject, _, stored = login(email, sorted(expected)[0])
        require(set(client.call('GET', '/bff/session')[1]['roles']) == expected,
                label + ' roles from real Auth')
        actors[label] = (client, stored, expected)

    # Every boundary runs with real cookie authentication. A real signed JWT presented by
    # the caller is no longer a credential: the web BFF rejects any external Authorization header.
    for label, (client, stored, roles) in actors.items():
        for method, path, destination, required in CASES:
            body = {'probe': 'gateway-role-journey'} if method != 'GET' else None
            before = len(fixtures.calls)
            status, headers, _ = client.request(method, path, body,
                                                headers={'X-Member-Id': 'spoofed-member'})
            allowed = not required or bool(required & roles)
            title = label + ' cookie ' + method + ' ' + path
            if allowed:
                require(status == 200 and len(fixtures.calls) == before + 1
                        and headers.get('X-Journey-Backend') == destination,
                        title + ' reaches expected backend')
                call = fixtures.calls[-1]
                require(call['authorization'] == 'Bearer ' + stored['access'], title + ' JWT relay')
                if destination in {'order', 'payment', 'license', 'download'} or '/api/v1/admin/products/' in path:
                    require(call['member'] is None, title + ' spoofed member removed')
            else:
                require(status == 403 and len(fixtures.calls) == before,
                        title + ' forbidden before backend')
            before = len(fixtures.calls)
            response = requests.request(method, base + path,
                                        headers={'Authorization': 'Bearer ' + stored['access']},
                                        json=body, timeout=10, allow_redirects=False)
            require(response.status_code == 401 and len(fixtures.calls) == before,
                    label + ' external bearer ' + method + ' ' + path + ' rejected before backend')

    for method, path, _, required in CASES:
        before = len(fixtures.calls)
        response = requests.request(method, base + path, timeout=10, allow_redirects=False)
        require((response.status_code in {401, 403} and len(fixtures.calls) == before) if required
                else (response.status_code == 200 and len(fixtures.calls) == before + 1),
                'anonymous ' + method + ' ' + path)

    client, _, _ = actors['member']
    for status in [202, 400, 404, 409, 422, 503]:
        code, headers, raw = client.request('POST', '/api/v1/payments/order-1/approvals?status=' + str(status),
                                            {'requestId': 'probe'})
        value = json.loads(raw)
        require(code == status and headers.get('Cache-Control') == 'no-store'
                and headers.get('X-Journey-Backend') == 'payment'
                and value['code'] == ('OK' if status == 202 else 'FIXTURE_ERROR'),
                'downstream status and JSON preserved ' + str(status))
        if status == 202:
            require(headers.get('Location') == '/api/v1/payments/order-1/approvals/request-1'
                    and value['state'] == 'PENDING', '202 Location and pending state preserved')

    # CSRF must stop real authenticated cookie writes, before any downstream call.
    before = len(fixtures.calls)
    require(client.request('POST', '/api/v1/orders', {}, csrf=False)[0] == 403
            and len(fixtures.calls) == before, 'cookie write without CSRF blocked')

    admin = actors['admin'][0]
    for method, path in [('POST', '/api/v1/products/quote'), ('GET', '/api/v1/ops/outbox'),
                         ('GET', '/internal/v1/store-products'), ('POST', '/v3/api-docs/catalog'),
                         ('GET', '/v3/api-docs/catalog/internal')]:
        before = len(fixtures.calls)
        status = admin.request(method, path, {} if method != 'GET' else None)[0]
        require(status in {401, 403, 404} and len(fixtures.calls) == before,
                'internal or wrong docs method blocked ' + method + ' ' + path)

    # Account management routes reach actual Auth, rather than HTTP fixtures.
    for label, (client, _, roles) in actors.items():
        for suffix, body in [('status', {'status': 'LOCKED'}), ('roles/grant', {'role': 'CREATOR'}),
                             ('roles/revoke', {'role': 'CREATOR'})]:
            status = client.request('POST', '/api/v1/admin/accounts/missing-account/' + suffix, body)[0]
            require(status == (404 if 'ADMIN' in roles else 403),
                    label + ' account admin ' + suffix)
