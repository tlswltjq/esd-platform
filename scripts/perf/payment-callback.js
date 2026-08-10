// 결제 콜백 — 팬아웃이 가장 큰 경로에서 **종단 지연**을 잰다.
//
//   docker run --rm --network stove_default -v "$PWD/scripts/perf:/perf" grafana/k6 \
//     run -e ORDER_URL=http://order:8082 -e PAYMENT_URL=http://payment:8083 \
//         -e LICENSE_URL=http://license:8084 /perf/payment-callback.js
//
// 절차는 scripts/perf/README.md 9장 기준이다(원격 전체 스택, k6 도 같은 네트워크의 컨테이너).
//
// ── 왜 이 시나리오가 필요했나 ────────────────────────────────────────
//
// 기존 셋(smoke · order-throughput · order-soak)이 전부 **주문 생성 한 경로**를 민다.
// 그래서 재는 것이 `http_req_duration` — 주문 API 응답까지다. 사용자가 느끼는 것은 그게 아니다.
// 결제 완료에서 라이선스 지급까지 Kafka 홉이 두 번, 릴레이 폴링이 두 번 낀다.
// **`http_req_duration` 과 `stove_outbox_pending` 이 둘 다 초록인데 종단 지연이 30초일 수 있다.**
//
// PaymentCompleted 는 license·order·settlement 로 갈라지고 license 가 낳은 LicenseIssued 가
// download 까지 간다. 릴레이가 포화되면 이 경로가 가장 먼저, 가장 크게 밀린다.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';
import { ORDER_URL, JSON_HEADERS, ON_SALE_PRODUCT_IDS } from './lib/config.js';

const PAYMENT_URL = __ENV.PAYMENT_URL || 'http://localhost:8083';
const LICENSE_URL = __ENV.LICENSE_URL || 'http://localhost:8084';

/** 결제 승인 응답 → 라이브러리에 라이선스가 보이기까지의 벽시계 시간(ms). */
const fulfillmentLatency = new Trend('e2e_fulfillment_latency', true);
/** 제한 시간 안에 지급이 확인된 비율. 지연 분포는 도달한 것만 말해 주므로 이 값이 함께 필요하다. */
const fulfilled = new Rate('e2e_fulfillment_ok');

const POLL_LIMIT_MS = 60_000;
const POLL_INTERVAL_S = 0.5;

/**
 * 폴링은 조건이 서기 전에 404 를 받는 것이 정상이다.
 *
 * k6 의 기본 판정으로는 그 404 가 `http_req_failed` 에 그대로 잡혀,
 * **아직 도착하지 않은 것**과 **시스템이 틀린 것**이 한 숫자에 섞인다.
 * 실측에서 18.6% 가 나왔는데 전부 정상 대기였다. 폴링 요청에만 기대 상태를 넓혀 준다.
 */
const POLLING = { responseCallback: http.expectedStatuses(200, 404) };

export const options = {
  scenarios: {
    // 낮게 시작한다. 이 시나리오의 목적은 최대 처리량이 아니라 **지연이 어떻게 번지는가**다.
    // 부하를 올리며 e2e_fulfillment_latency 가 무너지는 지점을 보는 것이 사용법이다.
    fanout: { executor: 'constant-vus', vus: 5, duration: '2m' },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    // 임계값을 세우되 넉넉하게 둔다. 여기서 잡으려는 것은 "느리다" 가 아니라 **끊겼다** 이다.
    e2e_fulfillment_ok: ['rate>0.95'],
  },
};

export default function () {
  const productId = ON_SALE_PRODUCT_IDS[Math.floor(Math.random() * ON_SALE_PRODUCT_IDS.length)];
  const memberId = 1000 + Math.floor(Math.random() * 100000);

  // 1. 주문 — 서버가 금액을 확정하므로 expectedAmount 는 그 값을 되받아 쓴다
  const quote = http.post(
    `${ORDER_URL}/api/v1/orders`,
    JSON.stringify({ memberId, items: [{ productId, quantity: 1 }] }),
    { headers: JSON_HEADERS },
  );
  if (!check(quote, { '주문 생성': (r) => r.status === 200 })) {
    return;
  }
  const orderNo = quote.json('data.orderNo');
  const amount = quote.json('data.totalAmount');

  // 2. 결제 대기가 생길 때까지 기다린다 — OrderCreated 가 Kafka 를 건너야 한다
  if (!waitFor(() => http.get(`${PAYMENT_URL}/api/v1/payments/${orderNo}`, POLLING).json('data.status') === 'READY')) {
    fulfilled.add(false);
    return;
  }

  // 3. 사전등록 → 승인 콜백
  http.post(`${PAYMENT_URL}/api/v1/payments/${orderNo}/prepare`,
    JSON.stringify({ method: 'CARD' }), { headers: JSON_HEADERS });

  const approvedAt = Date.now();
  const callback = http.post(
    `${PAYMENT_URL}/api/v1/payments/callback`,
    JSON.stringify({
      result: 'APPROVED',
      orderNo,
      pgTxId: `PG-PERF-${orderNo}`,
      paidAmount: amount,
      idempotencyKey: `IDEM-PERF-${orderNo}`,
    }),
    { headers: JSON_HEADERS },
  );
  if (!check(callback, { '결제 승인': (r) => r.status === 200 })) {
    fulfilled.add(false);
    return;
  }

  // 4. **여기가 재는 구간이다** — 승인이 끝난 시점부터 라이브러리에 보이기까지
  const ok = waitFor(() => hasLicense(memberId, orderNo));
  fulfilled.add(ok);
  if (ok) {
    fulfillmentLatency.add(Date.now() - approvedAt);
  }

  sleep(1);
}

function hasLicense(memberId, orderNo) {
  const library = http.get(`${LICENSE_URL}/api/v1/library`,
    { headers: { 'X-Member-Id': String(memberId) }, ...POLLING });
  if (library.status !== 200) {
    return false;
  }
  const licenses = library.json('data') || [];
  return licenses.some((license) => license.orderNo === orderNo);
}

/** 조건이 설 때까지 폴링한다. 고정 sleep 은 짧으면 거짓 실패고 길면 측정을 왜곡한다. */
function waitFor(condition) {
  const deadline = Date.now() + POLL_LIMIT_MS;
  while (Date.now() < deadline) {
    try {
      if (condition()) {
        return true;
      }
    } catch (e) {
      // 응답이 아직 봉투 형태가 아닐 수 있다. 다음 회차에 다시 본다.
    }
    sleep(POLL_INTERVAL_S);
  }
  return false;
}
