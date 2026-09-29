// 시나리오 공통 설정. 기준선 측정과 재측정이 같은 조건이어야 비교가 성립하므로
// 부하 형태를 스크립트에 고정하고 환경 변수로는 주소만 바꾼다.

export const ORDER_URL = __ENV.ORDER_URL || 'http://localhost:8082';
export const CATALOG_URL = __ENV.CATALOG_URL || 'http://localhost:8081';

/** V2__seed_products.sql 의 ON_SALE 상품. APPROVED 인 3번은 주문이 거절되므로 뺀다. */
export const ON_SALE_PRODUCT_IDS = [1, 2];

export const JSON_HEADERS = {
  'Content-Type': 'application/json',
  Authorization: `Bearer ${__ENV.MEMBER_TOKEN || ''}`,
};

/**
 * 주문 페이로드.
 *
 * <p>소유자는 요청 본문이 아니라 MEMBER_TOKEN의 서명된 member_id다.
 * 여러 회원의 분포가 필요한 측정은 서로 다른 토큰으로 별도 회차를 돌린다.
 */
export function orderPayload() {
  const productId = ON_SALE_PRODUCT_IDS[Math.floor(Math.random() * ON_SALE_PRODUCT_IDS.length)];
  return JSON.stringify({
    items: [{ productId, quantity: 1 }],
  });
}

/** 성공 응답 판정. HTTP 200 이어도 봉투의 success 가 false 면 실패다. */
export function isOrderCreated(response) {
  if (response.status !== 200) {
    return false;
  }
  try {
    const body = response.json();
    return body.success === true && body.data && typeof body.data.orderNo === 'string';
  } catch (e) {
    return false;
  }
}
