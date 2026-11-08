"""Gateway BFF cookie client (adapted from the 2026-10-30 G2 journey). Never sends bearer tokens."""
import http.cookiejar
import json
import re
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


class BffClient:
    def __init__(self, gateway):
        self.gateway = gateway
        self.cookies = http.cookiejar.CookieJar()
        self.client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies), urllib.request.ProxyHandler({}), NoRedirect())
        self.audit = []

    def request(self, method, path, body=None, form=False, csrf=True):
        headers = {}
        if method not in {"GET", "HEAD"} and not form and csrf:
            status, _, raw = self.request("GET", "/bff/csrf")
            assert status == 200, "CSRF token unavailable: " + str(status)
            token = json.loads(raw)
            headers[token["headerName"]] = token["token"]
        data = None
        if body is not None:
            data = (urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
        target = urllib.parse.urljoin(self.gateway, path)
        assert urllib.parse.urlsplit(target).netloc == urllib.parse.urlsplit(self.gateway).netloc, "calls must stay on Gateway"
        try:
            response = self.client.open(urllib.request.Request(target, data=data, headers=headers, method=method), timeout=30)
        except urllib.error.HTTPError as failure:
            response = failure
        with response:
            result = response.status, response.headers, response.read()
        self.audit.append({"method": method, "path": urllib.parse.urlsplit(target).path, "status": result[0]})
        return result

    def login(self, email, password, role="MEMBER"):
        response = self.request("GET", "/oauth2/authorization/gateway-bff")
        pkce = False
        for _ in range(8):
            location = response[1].get("Location", "")
            assert location, "authorization redirect missing"
            parsed = urllib.parse.urlsplit(location)
            if parsed.path == "/oauth2/authorize":
                parameters = urllib.parse.parse_qs(parsed.query)
                pkce = parameters.get("code_challenge_method") == ["S256"]
            response = self.request("GET", location)
            if parsed.path == "/login":
                break
        else:
            raise RuntimeError("login form not reached through Gateway")
        token = re.search(r'name="_csrf"[^>]*value="([^"]+)"', response[2].decode()).group(1)
        response = self.request("POST", "/login", {"username": email, "password": password, "_csrf": token}, form=True)
        for _ in range(10):
            location = response[1].get("Location", "")
            assert location, "login redirect missing"
            if urllib.parse.urlsplit(location).path == "/":
                break
            response = self.request("GET", location)
        else:
            raise RuntimeError("BFF login did not finish")
        status, _, raw = self.request("GET", "/bff/session")
        assert status == 200, "BFF session unavailable"
        session = json.loads(raw)
        assert pkce and role in session["roles"]
        return session["subject"]

    def call(self, method, path, body=None):
        status, _, raw = self.request(method, path, body)
        value = json.loads(raw) if raw else {}
        return status, value

    def data(self, path, expected=200):
        status, value = self.call("GET", path)
        if status != expected:
            raise RuntimeError("Gateway GET " + path + " returned HTTP " + str(status))
        return value.get("data")
