// 지속 부하. 적체가 해소되는지, 아니면 누적되는지를 본다.
//
//   k6 run scripts/perf/order-soak.js
//
// 계단식 시나리오(order-throughput.js)는 한계선을 찾지만, 그 한계 아래에서
// 시스템이 안정적인지는 알려주지 않는다. 릴레이가 유입을 겨우 따라가는 상태라면
// 짧은 측정에서는 정상으로 보이다가 몇 분 뒤 적체가 쌓인다.
//
// 판정 기준은 응답시간이 아니라 stove.outbox.pending 의 기울기다 —
// 우상향이면 처리량이 유입을 못 따라간다는 뜻이고, 그 경우 시간 문제일 뿐 반드시 터진다.

import http from 'k6/http';
import { check } from 'k6';
import { ORDER_URL, JSON_HEADERS, orderPayload, isOrderCreated } from './lib/config.js';

/**
 * 부하와 시간은 환경 변수로 조절한다.
 * 포화 지점 위에서 잰 숫자는 무엇을 바꿔도 "포화했다"만 알려주므로,
 * 구성 요소 간 비교는 반드시 포화 <b>이전</b> 구간에서 해야 한다.
 */
const RATE = Number(__ENV.RATE || 100);
const DURATION = __ENV.DURATION || '5m';

export const options = {
  scenarios: {
    soak: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
  thresholds: {
    // 응답이 200 이어도 봉투가 실패면 적체가 안 쌓이는 게 당연하다.
    // 논리 실패를 걸러야 stove.outbox.pending 의 기울기 판정이 성립한다.
    checks: ['rate>0.99'],
    // RATE 가 실제로 걸렸는지 보장한다. VU 가 모자라 버려진 유입은
    // 적체를 만들지 않으므로, 이 줄이 없으면 "안정적이다"와 구분되지 않는다.
    dropped_iterations: ['count<1'],
    http_req_failed: ['rate<0.01'],
    // 지속 부하에서는 꼬리 지연이 더 중요하다. 평균은 문제를 숨긴다.
    http_req_duration: ['p(99)<1000'],
  },
};

export default function () {
  const response = http.post(`${ORDER_URL}/api/v1/orders`, orderPayload(), {
    headers: JSON_HEADERS,
    tags: { endpoint: 'create_order' },
  });

  check(response, { '주문 생성': isOrderCreated });
}
