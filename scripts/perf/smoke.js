// 본 측정 전 게이트. 경로가 살아 있는지만 확인한다.
// 여기서 실패하면 부하 시나리오를 돌려봐야 숫자가 의미 없다.
//
//   k6 run scripts/perf/smoke.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { ORDER_URL, CATALOG_URL, JSON_HEADERS, orderPayload, isOrderCreated } from './lib/config.js';

export const options = {
  vus: 1,
  duration: '20s',
  thresholds: {
    // 스모크는 한 건도 실패하면 안 된다
    checks: ['rate==1.0'],
    http_req_failed: ['rate==0'],
  },
};

export default function () {
  const health = http.get(`${ORDER_URL}/actuator/health`);
  check(health, { 'order 살아있음': (r) => r.status === 200 });

  const quote = http.post(
    `${CATALOG_URL}/api/v1/products/quote`,
    JSON.stringify({ items: [{ productId: 1, quantity: 1 }] }),
    { headers: JSON_HEADERS },
  );
  check(quote, {
    'catalog 견적 응답': (r) => r.status === 200,
    '서버가 금액을 확정한다': (r) => r.json('data.totalAmount') > 0,
  });

  const order = http.post(`${ORDER_URL}/api/v1/orders`, orderPayload(), { headers: JSON_HEADERS });
  check(order, { '주문 생성': isOrderCreated });

  sleep(1);
}
