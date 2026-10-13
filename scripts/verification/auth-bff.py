#!/usr/bin/env python3
"""Live auth/BFF smoke test. Requires local Compose Redis and 30-second BFF access tokens.
Creates test accounts; never prints credentials, authorization codes or OAuth tokens.
"""
import base64
import concurrent.futures
import http.cookiejar
from html.parser import HTMLParser
import json
import os
import re
import secrets
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get('BFF_TEST_BASE', 'http://localhost:8080')
PEER = os.environ.get('BFF_TEST_PEER', 'http://localhost:8081')
AUTH = os.environ.get('BFF_TEST_AUTH', 'http://localhost:8091')
SECRET = os.environ['BFF_CLIENT_SECRET']

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None

class Browser:
    def __init__(self):
        self.cookies = http.cookiejar.CookieJar()
        self.client = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.cookies), NoRedirect())

    def request(self, path, data=None, headers=None, as_json=False):
        headers = dict(headers or {})
        body = None
        if data is not None:
            body = (json.dumps(data) if as_json else urllib.parse.urlencode(data)).encode()
            headers['Content-Type'] = 'application/json' if as_json else 'application/x-www-form-urlencoded'
        try:
            response = self.client.open(urllib.request.Request(
                urllib.parse.urljoin(BASE, path), body, headers), timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        return response.code, response.headers, response.read().decode()

    def login(self, callback_on_peer=False):
        email = 'bff-check-' + secrets.token_hex(6) + '@example.com'
        password = secrets.token_urlsafe(24)
        assert self.request('/api/v1/auth/signup/member',
                            dict(email=email, password=password), as_json=True)[0] == 200
        response = self.request('/oauth2/authorization/gateway-bff')
        for _ in range(5):
            location = response[1].get('Location', '')
            assert location, 'Missing authorization redirect'
            if urllib.parse.urlparse(location).path == '/login':
                break
            response = self.request(location)
        page = self.request(location)
        csrf = re.search(r'name="_csrf"[^>]*value="([^"]+)"', page[2]).group(1)
        bad = self.request('/login', dict(username=email, password='wrong', _csrf=csrf))
        assert '/login?error' in bad[1].get('Location', ''), 'Wrong password accepted'
        page = self.request('/login')
        csrf = re.search(r'name="_csrf"[^>]*value="([^"]+)"', page[2]).group(1)
        response = self.request('/login', dict(username=email, password=password, _csrf=csrf))
        for _ in range(7):
            location = response[1].get('Location', '')
            assert location, 'Login callback failed'
            if urllib.parse.urlparse(location).path == '/':
                break
            if callback_on_peer and urllib.parse.urlparse(location).path.startswith('/login/oauth2/code/'):
                uri = urllib.parse.urlparse(location)
                location = PEER + uri.path + '?' + uri.query
                # Emulates a load balancer's original external host during callback.
                response = self.request(location, headers={
                    'X-Forwarded-Host': urllib.parse.urlparse(BASE).netloc,
                    'X-Forwarded-Proto': urllib.parse.urlparse(BASE).scheme})
            else:
                response = self.request(location)
        assert response[0] == 302
        response = self.request('/bff/session')
        assert response[0] == 200, 'BFF session unavailable'
        user = json.loads(response[2])
        assert 'access_token' not in user and 'refresh_token' not in user
        assert 'SESSION' in [cookie.name for cookie in self.cookies]
        return user

def redis(*args):
    return subprocess.check_output(['docker', 'compose', 'exec', '-T', 'redis',
                                    'redis-cli', '--raw', *args], text=True).strip()

def stored(subject):
    for key in redis('--scan', '--pattern', 'stove:bff:tokens:*').splitlines():
        if key.endswith('refresh-lock'):
            continue
        value = redis('GET', key)
        if value:
            record = json.loads(value)
            if record['subject'] == subject:
                return key, record
    raise AssertionError('Server-side tokens missing')

def refresh(token):
    basic = base64.b64encode(('gateway-bff:' + SECRET).encode()).decode()
    return Browser().request(AUTH + '/oauth2/token',
                             dict(grant_type='refresh_token', refresh_token=token),
                             {'Authorization': 'Basic ' + basic})

def main():
    browser = Browser()
    user = browser.login(callback_on_peer=True)
    assert browser.request(PEER + '/bff/session')[0] == 200
    assert browser.request('/api/v1/auth/session')[0] == 200, 'Bearer relay to auth failed'
    assert browser.request('/bff/logout', {})[0] == 403
    assert Browser().request('/bff/session')[0] == 401
    assert Browser().request(AUTH + '/favicon.ico')[0] == 404
    print('PASS: automatic PKCE, cross-instance callback/session, bearer relay, CSRF, 404')

    key, initial = stored(user['subject'])
    remaining = initial['expires'] - time.time()
    assert remaining <= 40, 'Run auth with BFF_ACCESS_TOKEN_SECONDS=30 for this test'
    time.sleep(max(0, remaining - 8))
    cookie_header = '; '.join(cookie.name + '=' + cookie.value for cookie in browser.cookies)
    def concurrent_request(index):
        return Browser().request((BASE if index % 2 == 0 else PEER) + '/bff/session',
                                 headers={'Cookie': cookie_header})[0]
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        assert list(pool.map(concurrent_request, range(8))) == [200] * 8
    _, updated = stored(user['subject'])
    assert initial['refresh'] != updated['refresh']
    assert refresh(initial['refresh'])[0] == 400
    assert refresh(updated['refresh'])[0] == 400
    print('PASS: concurrent rotation across two gateways and family revocation on replay')

    pieces = updated['access'].split('.')
    pieces[2] = ('A' if pieces[2][0] != 'A' else 'B') + pieces[2][1:]
    assert Browser().request('/api/v1/orders/1', headers={
        'Authorization': 'Bearer ' + '.'.join(pieces)})[0] == 401
    print('PASS: tampered JWT rejected')

    logout_browser = Browser()
    logout_user = logout_browser.login()
    key, tokens = stored(logout_user['subject'])
    csrf = json.loads(logout_browser.request('/bff/csrf')[2])
    response = logout_browser.request('/bff/logout', {}, {csrf['headerName']: csrf['token']})
    assert response[0] == 302, 'BFF logout failed'
    assert '/connect/logout' in response[1].get('Location', ''), 'Missing OIDC logout redirect'
    assert redis('GET', key) == ''
    assert logout_browser.request('/bff/session')[0] == 401
    assert refresh(tokens['refresh'])[0] == 400
    location = response[1]['Location']
    confirmation = logout_browser.request(location)
    if confirmation[0] == 200:
        class Form(HTMLParser):
            def __init__(self):
                super().__init__(); self.fields = {}; self.action = '/connect/logout'
            def handle_starttag(self, tag, attributes):
                values = dict(attributes)
                if tag == 'form': self.action = values.get('action', self.action)
                if tag == 'input' and values.get('name'):
                    self.fields[values['name']] = values.get('value', '')
        form = Form(); form.feed(confirmation[2])
        confirmation = logout_browser.request(form.action, form.fields)
    assert confirmation[0] == 302, 'auth logout failed'
    anonymous = logout_browser.request('/oauth2/authorize?' + urllib.parse.urlencode({
        'client_id': 'gateway-bff', 'response_type': 'code', 'redirect_uri': BASE + '/login/oauth2/code/gateway-bff',
        'scope': 'openid', 'code_challenge': 'a' * 43, 'code_challenge_method': 'S256'}))
    assert '/login' in anonymous[1].get('Location', ''), 'auth login session still active'
    print('PASS: logout deletes BFF session/tokens, revokes refresh family and ends auth session')
    session_file = os.environ.get('BFF_TEST_SESSION_FILE')
    if session_file:
        restart_browser = Browser(); restart_user = restart_browser.login()
        with open(session_file, 'w') as file:
            json.dump({'cookies': {cookie.name: cookie.value for cookie in restart_browser.cookies},
                       'subject': restart_user['subject']}, file)
        os.chmod(session_file, 0o600)

if __name__ == '__main__':
    main()
