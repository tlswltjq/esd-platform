import base64
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import time
import uuid

import requests

ROOT = Path(__file__).resolve().parents[1]
RUN = Path(os.environ['KIROCREW_SCRATCH']) / ('auth-journey-' + uuid.uuid4().hex[:8])
RUN.mkdir()
PREFIX = RUN.name
PASSWORD = 'journey-password-123'
processes = []
containers = []
observations = []
role_mode = '--roles' in sys.argv[1:]
role_fixtures = None


def cmd(*args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs).strip()


def inventory():
    return {'containers': sorted(cmd('docker', 'ps', '-aq').splitlines()),
            'volumes': sorted(cmd('docker', 'volume', 'ls', '-q').splitlines())}


def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def require(condition, label):
    if not condition:
        raise AssertionError(label)
    observations.append(label)
    print('PASS ' + label, flush=True)


def wait_for(probe, label):
    deadline = time.monotonic() + 100
    while time.monotonic() < deadline:
        try:
            if probe():
                return
        except Exception:
            pass
        time.sleep(1)
    raise AssertionError('startup: ' + label)


def sql(statement):
    return cmd('docker', 'exec', mysql, 'mysql', '-uroot', '-pjourney-root',
               '-N', '-B', 'stove_auth', '-e', statement, stderr=subprocess.DEVNULL)


def redis(*args):
    return cmd('docker', 'exec', cache, 'redis-cli', '--raw', *args)


def launch(module, env):
    jar = next(p for p in (ROOT / 'apps' / module / 'build/libs').glob('*.jar')
               if not p.name.endswith('-plain.jar'))
    log = (RUN / (module + '.log')).open('w')
    process = subprocess.Popen(['java', '-Xmx384m', '-jar', str(jar)],
                               cwd=ROOT, env={**os.environ, **env}, stdout=log, stderr=log)
    processes.append(process)


def login(email):
    session = requests.Session()
    session.trust_env = False
    page = session.get(base + '/oauth2/authorization/gateway-bff', timeout=15)
    csrf = re.search(r'name="_csrf"[^>]*value="([^"]+)"', page.text)
    require(csrf is not None, 'login form csrf')
    session.post(base + '/login', data={'username': email, 'password': PASSWORD,
                                      '_csrf': csrf.group(1)}, timeout=15)
    response = session.get(base + '/bff/session', timeout=10)
    require(response.status_code == 200, 'BFF login ' + email.split('@')[0])
    subject = response.json()['subject']
    for key in redis('KEYS', 'stove:bff:tokens:*').splitlines():
        stored = json.loads(redis('GET', key))
        if stored['subject'] == subject:
            return session, subject, key, stored
    raise AssertionError('stored authorized client missing')


before = inventory()
(RUN / 'before.json').write_text(json.dumps(before, indent=2))
try:
    dbport, redisport, authport, gatewayport = (port() for _ in range(4))
    mysql, cache = PREFIX + '-mysql', PREFIX + '-redis'
    for name, image, args in [
        (mysql, 'mysql:8.0', ['-p', f'127.0.0.1:{dbport}:3306', '-e',
                            'MYSQL_ROOT_PASSWORD=journey-root', '-e', 'MYSQL_DATABASE=stove_auth']),
        (cache, 'redis:7-alpine', ['-p', f'127.0.0.1:{redisport}:6379']),
    ]:
        cmd('docker', 'run', '-d', '--rm', '--name', name, *args, image)
        containers.append(name)
    wait_for(lambda: sql('SELECT 1') == '1', 'mysql')
    base = f'http://127.0.0.1:{gatewayport}'
    auth = f'http://127.0.0.1:{authport}'
    common = {'BFF_CLIENT_SECRET': 'journey-client-secret', 'SERVER_ADDRESS': '127.0.0.1'}
    launch('auth', {**common, 'SERVER_PORT': str(authport),
                   'DB_PORT': str(dbport), 'DB_HOST': '127.0.0.1', 'DB_USER': 'root',
                   'DB_PASSWORD': 'journey-root', 'AUTH_ISSUER': auth,
                   'BFF_REDIRECT_URI': base + '/login/oauth2/code/gateway-bff',
                   'BFF_ACCESS_TOKEN_SECONDS': '900' if role_mode else '25', 'STOVE_OUTBOX_RELAY_ENABLED': 'false',
                   'AUTH_REVIEWER_EMAIL': 'reviewer@journey.example' if role_mode else '',
                   'AUTH_REVIEWER_PASSWORD': PASSWORD if role_mode else '',
                   'AUTH_ADMIN_EMAIL': 'admin@journey.example', 'AUTH_ADMIN_PASSWORD': PASSWORD})
    wait_for(lambda: requests.get(auth + '/actuator/health', timeout=2).status_code == 200, 'auth')
    if role_mode:
        from gateway_role_journey import Backends, verify
        role_fixtures = Backends()
    launch('gateway', {**common, **(role_fixtures.environment if role_mode else {}), 'SERVER_PORT': str(gatewayport),
                      'REDIS_HOST': '127.0.0.1', 'REDIS_PORT': str(redisport),
                      'AUTH_URI': auth, 'JWT_ISSUER_URI': auth,
                      'JWT_JWK_SET_URI': auth + '/oauth2/jwks', 'BFF_PUBLIC_AUTH_BASE': base})
    wait_for(lambda: requests.get(base + '/bff/csrf', timeout=2).status_code == 200, 'gateway')
    if role_mode:
        verify(base, login, require, role_fixtures, PASSWORD)
    for scenario in ([] if role_mode else ['recovery', 'locked', 'role-revoked']):
        email = scenario + '@journey.example'
        response = requests.post(base + '/api/v1/auth/signup/member',
                                 json={'email': email, 'password': PASSWORD}, timeout=10)
        require(response.status_code == 200, scenario + ' signup')
        if scenario == 'role-revoked':
            sql("INSERT INTO user_account_role (user_account_id,role) SELECT id,'CREATOR' FROM user_account WHERE email='" + email + "'")
        session, subject, key, stored = login(email)
        cookie = session.cookies.get('SESSION')
        try:
            session_id = str(uuid.UUID(cookie))
        except ValueError:
            session_id = base64.b64decode(cookie + '=' * (-len(cookie) % 4)).decode()
        session_key = 'stove:bff:sessions:sessions:' + session_id
        require(redis('EXISTS', session_key) == '1', scenario + ' Redis session exists')
        if scenario == 'recovery':
            response = requests.post(base + '/api/v1/auth/password/reset-request', json={'email': email}, timeout=10)
            require(response.status_code == 200, 'recovery request accepted')
            require(sql("SELECT COUNT(*) FROM password_reset_token WHERE subject='" + subject + "'") == '1', 'recovery request hash persisted')
            # Mock mail deliberately does not expose its token. A known, hashed test
            # fixture exercises reset HTTP without logging or extracting a secret.
            token = uuid.uuid4().hex
            digest = hashlib.sha256(token.encode()).hexdigest()
            sql("INSERT INTO password_reset_token VALUES ('" + digest + "','" + subject + "',TIMESTAMPADD(MINUTE,30,CURRENT_TIMESTAMP),FALSE)")
            response = requests.post(base + '/api/v1/auth/password/reset',
                                     json={'token': token, 'newPassword': 'recovered-password-123'}, timeout=10)
            require(response.status_code == 200, 'recovery reset HTTP accepted')
        else:
            admin_session, actor, _, admin = login('admin@journey.example')
            action = 'status' if scenario == 'locked' else 'roles/revoke'
            body = {'status': 'LOCKED'} if scenario == 'locked' else {'role': 'CREATOR'}
            response = requests.post(base + '/api/v1/admin/accounts/' + subject + '/' + action,
                                     headers={'Authorization': 'Bearer ' + admin['access']}, json=body, timeout=10)
            require(response.status_code == 200, scenario + ' ADMIN mutation HTTP accepted')
            expected_action = 'STATUS_CHANGED' if scenario == 'locked' else 'ROLE_CHANGED'
            require(sql("SELECT COUNT(*) FROM role_audit_log WHERE subject='" + subject + "' AND actor='" + actor + "' AND action='" + expected_action + "'") == '1', scenario + ' actor audit persisted')
        require(sql("SELECT COUNT(*) FROM refresh_token_family WHERE subject='" + subject + "' AND revoked=FALSE") == '0', scenario + ' all refresh families revoked')
        # Wait until the real access token enters Gateway's refresh window.
        time.sleep(max(0, stored['expires'] - time.time() - 8))
        response = session.get(base + '/bff/session', timeout=10)
        require(response.status_code == 401, scenario + ' browser rejected after refresh')
        require(redis('EXISTS', key) == '0', scenario + ' Redis tokens deleted')
        require(redis('EXISTS', session_key) == '0', scenario + ' Redis browser session deleted')
        require(session.get(base + '/bff/session', timeout=10).status_code == 401,
                scenario + ' next browser request stays rejected')
    print('JOURNEY SUCCESS', flush=True)
finally:
    for process in reversed(processes):
        process.terminate()
        try:
            process.wait(timeout=40)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
    for name in reversed(containers):
        subprocess.run(['docker', 'stop', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if role_fixtures is not None:
        role_fixtures.close()
    after = inventory()
    (RUN / 'after.json').write_text(json.dumps(after, indent=2))
    (RUN / 'results.json').write_text(json.dumps({'passed': observations, 'resources_preserved': before == after}, indent=2))
    require(before == after, 'existing containers and volumes preserved')
    print('RESULTS ' + str(RUN / 'results.json'), flush=True)
