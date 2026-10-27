#!/usr/bin/env python3
"""Open one standalone PortOne test checkout on loopback; no business DB is used."""

import argparse
import hmac
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
from pathlib import Path
import secrets
import tempfile
import urllib.parse

from support.portone_checkout import CheckoutProbe, PortOneClient, ProbeError, Settings


ROOT = Path(__file__).resolve().parents[2]
PAGE = Path(__file__).parent / 'support' / 'portone_checkout.html'


def make_handler(probe, token):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            pass

        def reply(self, status, body, content_type='application/json; charset=utf-8'):
            data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Referrer-Policy', 'no-referrer')
            self.send_header('X-Frame-Options', 'DENY')
            self.end_headers()
            self.wfile.write(data)

        def local_host(self):
            return self.headers.get('Host') == '127.0.0.1:' + str(self.server.server_port)

        def do_GET(self):
            if not self.local_host():
                self.reply(403, {'code': 'LOCAL_HOST_REQUIRED'})
                return
            path = urllib.parse.urlsplit(self.path).path
            if path == '/':
                self.reply(200, PAGE.read_bytes(), 'text/html; charset=utf-8')
            elif path == '/api/config':
                config = probe.public_config()
                config['token'] = token
                config['payment']['redirectUrl'] = 'http://127.0.0.1:' + str(self.server.server_port) + '/'
                self.reply(200, config)
            else:
                self.reply(404, {'code': 'NOT_FOUND'})

        def do_POST(self):
            origin = 'http://127.0.0.1:' + str(self.server.server_port)
            supplied_token = self.headers.get('X-Verification-Token', '')
            if (not self.local_host() or self.headers.get('Origin') != origin
                    or not hmac.compare_digest(supplied_token, token)):
                self.reply(403, {'code': 'LOCAL_SESSION_REQUIRED'})
                return
            if self.headers.get('Content-Type') != 'application/json':
                self.reply(415, {'code': 'JSON_REQUIRED'})
                return
            try:
                size = int(self.headers.get('Content-Length', '0'))
                if not 0 < size <= 1024 or json.loads(self.rfile.read(size)) != {}:
                    self.reply(400, {'code': 'EMPTY_JSON_OBJECT_REQUIRED'})
                    return
            except (ValueError, TypeError):
                self.reply(400, {'code': 'INVALID_REQUEST'})
                return
            try:
                if self.path == '/api/verify':
                    self.reply(200, probe.verify())
                elif self.path == '/api/cancel':
                    self.reply(200, probe.cancel())
                else:
                    self.reply(404, {'code': 'NOT_FOUND'})
            except ProbeError as error:
                self.reply(409, {'code': str(error)})
            except (OSError, ValueError, KeyError, TypeError):
                self.reply(503, {'code': 'LOCAL_VERIFICATION_ERROR'})

    return Handler


def make_server(probe, port):
    return HTTPServer(('127.0.0.1', port), make_handler(probe, secrets.token_urlsafe(32)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=18792)
    parser.add_argument('--run-dir', type=Path, help='Reuse this run directory after restart.')
    args = parser.parse_args()
    settings = Settings.load(ROOT / '.env.portone-test')
    runs = ROOT / 'runs'
    runs.mkdir(exist_ok=True)
    run_dir = args.run_dir or Path(tempfile.mkdtemp(prefix='portone-checkout-', dir=runs))
    probe = CheckoutProbe(settings, run_dir, PortOneClient(settings))
    server = make_server(probe, args.port)
    print('PortOne test checkout: http://127.0.0.1:' + str(server.server_port), flush=True)
    print('Evidence / resume directory: ' + str(run_dir.resolve()), flush=True)
    print('No business order, entitlement, or settlement data is changed.', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
