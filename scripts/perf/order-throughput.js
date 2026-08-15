// 주 시나리오. 유입량을 계단식으로 올려 Outbox 릴레이의 처리량 한계선을 찾는다.
//
//   k6 run scripts/perf/order-throughput.js
//   k6 run --out json=baseline.json scripts/perf/order-throughput.js
//
// 도착률(arrival-rate) 기반인 이유 — VU 기반으로 돌리면 응답이 느려질 때 유입도 같이
// 줄어들어 병목이 스스로 가려진다. 부하를 고정해야 적체가 드러난다.
//
// 주문 1건 = Outbox 이벤트 1건(OrderCreated) 이므로, 여기서의 RPS 가 곧 릴레이 유입량이다.

import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import { ORDER_URL, JSON_HEADERS, orderPayload, isOrderCreated } from './lib/config.js';

/** 단계별로 나눠 보기 위한 별도 지표 */
const orderLatency = new Trend('order_create_latency', true);

export const options = {
  discardResponseBodies: false,
  scenarios: {
    ramp: {
      executor: 'ramping-arrival-rate',
      startRate: 20,
      timeUnit: '1s',
      // 유입이 늘어도 VU 가 모자라 부하가 안 걸리는 일이 없도록 넉넉히
      preAllocatedVUs: 50,
      maxVUs: 400,
      stages: [
        { target: 20, duration: '20s' },   // 워밍업 — JIT, 커넥션 풀, 캐시
        { target: 50, duration: '60s' },
        { target: 100, duration: '60s' },
        { target: 200, duration: '60s' },
        { target: 400, duration: '60s' },
        { target: 0, duration: '10s' },    // 램프다운 — 적체 해소 관찰
      ],
    },
  },
  thresholds: {
    // check 는 세기만 할 뿐 종료 코드를 바꾸지 않는다. 이 줄이 있어야 아래 isOrderCreated 가
    // 게이트가 된다 — 200 OK + 봉투 success:false 는 http_req_failed 에 잡히지 않으므로,
    // 이 줄이 없으면 전 주문이 거절돼도 지연 지표는 오히려 좋아지며 초록으로 통과한다.
    checks: ['rate>0.99'],
    // 도착률 기반에서는 필수다. maxVUs 가 모자라면 k6 는 요청을 보내지 않고 버린다.
    // 목표 400 RPS 인데 실제로 120 만 나가도 http_req_duration 은 예쁘게 나온다 —
    // 즉 이 줄이 없으면 "그 부하가 실제로 걸렸다"를 스크립트가 보장하지 못한다.
    dropped_iterations: ['count<1'],
    // 기준선 측정 전이라 근거가 없는 값이다. 1차 측정 후 확정한다.
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<300', 'p(99)<800'],
  },
};

export default function () {
  const response = http.post(`${ORDER_URL}/api/v1/orders`, orderPayload(), {
    headers: JSON_HEADERS,
    tags: { endpoint: 'create_order' },
  });

  orderLatency.add(response.timings.duration);
  check(response, { '주문 생성': isOrderCreated });
}

export function teardown() {
  // 부하가 멈춘 뒤 릴레이가 적체를 얼마나 빨리 비우는지는
  // /actuator/prometheus 의 stove.outbox.pending 으로 확인한다.
  // (scripts/perf/collect-outbox.sh 가 같은 타임라인으로 수집한다)
  console.log('부하 종료. 적체 해소는 stove.outbox.pending 지표로 확인할 것.');
}
