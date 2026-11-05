"""Actual service ownership reads; immutable purchase fixtures in isolated SQL only."""

import json
import re
import uuid
import requests

checks = []
accounts = []
success = False


def check(ok, label):
    if not ok:
        raise AssertionError(label)
    checks.append(label)
    print('PASS ' + label, flush=True)


def login(email, password):
    client = requests.Session()
    client.trust_env = False
    page = client.get(base + '/oauth2/authorization/gateway-bff', timeout=20)
    token = re.search(r'name="_csrf"[^>]*value="([^"]+)"', page.text)
    check(token is not None, 'real login form')
    client.post(base + '/login', data={'username': email, 'password': password,
                                     '_csrf': token.group(1)}, timeout=20)
    response = client.get(base + '/bff/session', timeout=20)
    check(response.status_code == 200, 'real BFF login')
    csrf = client.get(base + '/bff/csrf', timeout=20).json()
    client.headers[csrf['headerName']] = csrf['token']
    return client, response.json()['subject']


def account(creator=False):
    email = 'ownership-' + uuid.uuid4().hex + '@example.com'
    password = 'ownership-password-1234'
    path = '/api/v1/auth/signup' + ('' if creator else '/member')
    check(requests.post(base + path, json={'email': email, 'password': password},
                        timeout=20).status_code == 200, 'registration')
    return login(email, password)


def quote(value):
    if isinstance(value, int):
        return str(value)
    return "'" + str(value).replace("'", "''") + "'"


def insert(schema, table, values):
    rows = sql("SELECT COLUMN_NAME,IS_NULLABLE,COALESCE(COLUMN_DEFAULT,'<NULL>'),EXTRA "
               "FROM information_schema.columns WHERE table_schema='" + schema + "' "
               "AND table_name='" + table + "' ORDER BY ORDINAL_POSITION").splitlines()
    columns = {}
    for row in rows:
        name, nullable, default, extra = (row.split('\t') + [''])[:4]
        columns[name] = (nullable, default, extra)
    check(bool(columns), 'current schema ' + table)
    missing = [name for name, (nullable, default, extra) in columns.items()
               if nullable == 'NO' and default == '<NULL>' and not extra
               and name not in values]
    if missing:
        raise AssertionError('fixture missing required columns: ' + table + ' ' + ','.join(missing))
    selected = {key: value for key, value in values.items() if key in columns}
    sql('INSERT INTO ' + schema + '.' + table + ' (' + ','.join(selected) + ') VALUES ('
        + ','.join(quote(value) for value in selected.values()) + ')')


def data(response, expected, label):
    check(response.status_code == expected, label + ' status ' + str(response.status_code))
    return response.json().get('data') if expected == 200 else None


def content(response, label, field='content'):
    body = data(response, 200, label)
    return body[field]


try:
    buyer_a, subject_a = account()
    buyer_b, subject_b = account()
    creator_a, seller_a = account(True)
    creator_b, seller_b = account(True)
    reviewer, _ = login('reviewer-check@review.local', 'reviewer-password-1234')
    admin, _ = login('admin-check@review.local', 'admin-password-1234')
    accounts = [buyer_a, buyer_b, creator_a, creator_b, reviewer, admin]
    now = '2026-11-05 00:00:00.000000'
    sources = []
    # These are DB fixtures, not proof of payment, license delivery, or PG execution.
    # No outbox or broker events are inserted, and SQL constraints remain enabled.
    for index, (subject, seller) in enumerate([(subject_a, seller_a), (subject_b, seller_b)], 1):
        order_no, request, event, result, payment_id = (str(uuid.uuid4()) for _ in range(5))
        code = 'OWNERSHIP-' + str(index)
        common = dict(order_no=order_no, buyer_subject=subject, request_id=str(uuid.uuid4()),
                      request_event_id=request, event_id=event, payment_result_event_id=result,
                      payment_id=payment_id, product_id=index, product_code=code,
                      title='Ownership fixture ' + str(index), content_version=1,
                      list_amount=10000, discount_amount=0, amount=10000, currency='KRW', status='PAID',
                      provider='SIMULATED', provider_transaction_id='ownership-tx-' + str(index),
                      provider_occurred_at=now, result_event_id=result, result_occurred_at=now,
                      created_at=now, updated_at=now, order_occurred_at=now, occurred_at=now,
                      expires_at='2026-12-05 00:00:00.000000', acquired_at=now, received_at=now,
                      license_id=str(uuid.uuid4()), seller_subject=seller,
                      platform_policy_id='OWNERSHIP_FIXTURE', platform_fee_rate_bps=1000,
                      platform_fee_amount=1000, seller_payout_amount=9000,
                      sale_month='2026-11', settlement_month='2026-11')
        insert('stove_order', 'purchase_order', common)
        insert('stove_payment', 'purchase_payment', common)
        insert('stove_license', 'product_license', {**common, 'status': 'ACTIVE'})
        insert('stove_settlement', 'settlement_pg_transaction', common)
        insert('stove_settlement', 'settlement_sale', common)
        sources.append((order_no, code))

    for owner, other, index in [(buyer_a, buyer_b, 0), (buyer_b, buyer_a, 1)]:
        order_no, code = sources[index]
        owner_subject = subject_a if index == 0 else subject_b
        owner_id = sql("SELECT id FROM stove_auth.user_account WHERE subject=" + quote(owner_subject))
        for prefix, suffix in [('/api/v1/orders/', order_no), ('/api/v1/payments/', order_no),
                               ('/api/v1/library/', code)]:
            body = data(owner.get(base + prefix + suffix, timeout=20), 200, 'owner ' + prefix)
            check(body.get('productCode') == code, 'owner snapshot ' + prefix)
            data(other.get(base + prefix + suffix, timeout=20), 404, 'other hidden ' + prefix)
            response = other.get(base + prefix + suffix, headers={'X-Member-Id': owner_id}, timeout=20)
            data(response, 404, 'spoofed header cannot own ' + prefix)
        for path in ['/api/v1/orders', '/api/v1/payments', '/api/v1/library']:
            rows = content(owner.get(base + path, timeout=20), 'owner list ' + path)
            check(len(rows) == 1 and rows[0]['productCode'] == code, 'list isolated ' + path)

    projects = []
    for client, other, seller in [(creator_a, creator_b, seller_a), (creator_b, creator_a, seller_b)]:
        response = client.post(base + '/api/v1/studio/projects', json={
            'productCode': 'creator-' + uuid.uuid4().hex, 'title': 'Ownership project'}, timeout=20)
        check(response.status_code == 201, 'real Studio project creation')
        project = response.json()['data']
        projects.append(project)
        path = '/api/v1/studio/projects/' + str(project['id'])
        data(client.get(base + path, timeout=20), 200, 'creator own project')
        data(other.get(base + path, timeout=20), 403, 'creator other project denied')
        rows = content(client.get(base + '/api/v1/studio/projects', timeout=20), 'creator projects', 'items')
        check(len(rows) == 1 and rows[0]['id'] == project['id'], 'creator projects isolated')
        response = client.get(base + '/api/v1/settlements/me/sales?sellerSubject=' + (
            seller_b if seller == seller_a else seller_a), timeout=20)
        rows = content(response, 'creator settlement')
        expected_code = sources[0 if seller == seller_a else 1][1]
        check(len(rows) == 1 and rows[0]['productCode'] == expected_code,
              'creator settlement ignores other seller query')

    for client, name in [(reviewer, 'reviewer'), (admin, 'admin')]:
        data(client.get(base + '/api/v1/reviews', timeout=20), 200, name + ' review list')
        data(client.post(base + '/api/v1/reviews/999999/approve', timeout=20), 404,
             name + ' reaches actual review missing request')
    for client in [buyer_a, creator_a]:
        data(client.get(base + '/api/v1/reviews', timeout=20), 403, 'non reviewer denied')
        data(client.get(base + '/api/v1/admin/refunds', timeout=20), 403, 'non admin denied')
    data(reviewer.get(base + '/api/v1/admin/refunds', timeout=20), 403, 'reviewer not admin')
    data(admin.get(base + '/api/v1/admin/refunds', timeout=20), 200, 'admin actual refund list')
    all_sales = content(admin.get(base + '/api/v1/settlements/sales', timeout=20), 'admin actual sales')
    check(len(all_sales) == 2, 'admin sees both sellers')
    data(creator_a.get(base + '/api/v1/settlements/sales', timeout=20), 403, 'creator not settlement admin')
    data(buyer_a.get(base + '/api/v1/settlements/me/sales', timeout=20), 403, 'member not creator')
    check(requests.get(base + '/api/v1/orders', timeout=20).status_code == 401, 'anonymous order denied')
    success = True
    print('OWNERSHIP JOURNEY SUCCESS', flush=True)
finally:
    (directory / 'ownership-results.json').write_text(json.dumps({'success': success, 'passed': checks}, indent=2))
    for account in accounts:
        account.close()
