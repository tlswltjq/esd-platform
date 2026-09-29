# 이슈 #78: 구매·결제·환불 API 데모

## 준비

`docker-compose.apps.yml`, `docker-compose.apps.ci.yml`, `docker-compose.apps.e2e.yml` 순으로 적용한 로컬 스택은 Payment를 `demo` 프로필로 실행하고 Gateway를 `http://127.0.0.1:18080`에 연다. `STOVE_PAYMENT_SIMULATOR_BASE_URL`은 구매자 브라우저에서 접근 가능한 Gateway 주소다. `demo` 또는 `test` 프로필에서만 시뮬레이터 제어 API가 생성되고, `prod` 프로필이 함께 켜져도 생성되지 않는다. 프로필 없이 실행하면 기존 mock 결제창 URL만 반환한다.

먼저 [창작자 출시 절차](p3-creator-api.md)로 BASIC/KRW 상품 한 개를 출시한다. `POST /api/v1/auth/signup/member`로 구매자를 만들고 [OAuth2 Authorization Code + PKCE](p3-commerce-security.md)로 `commerce` 범위의 회원 access token을 받는다. 운영자 토큰은 시뮬레이터 제어에만 쓴다. 아래 변수는 `BASE`, `PRODUCT_CODE`, `MEMBER_TOKEN`, `ADMIN_TOKEN`이다. 각 응답은 `success/data/error` 봉투이며, 명령은 `jq`와 `curl`을 사용한다.

## 구매와 지급

```sh
PRODUCT=$(curl -fsS "$BASE/api/v1/products/by-code/$PRODUCT_CODE" | jq -c '.data')
printf '%s' "$PRODUCT" | jq '{productId,productKind,price,currency,purchasable}'
PRODUCT_ID=$(printf '%s' "$PRODUCT" | jq -r '.productId')
PRICE=$(printf '%s' "$PRODUCT" | jq -r '.price')
ORDER_NO=$(jq -nc --argjson id "$PRODUCT_ID" --argjson price "$PRICE" \
  '{items:[{productId:$id,quantity:1}],expectedAmount:$price}' | \
  curl -fsS -H "Authorization: Bearer $MEMBER_TOKEN" -H 'Content-Type: application/json' \
    -d @- "$BASE/api/v1/orders" | jq -r '.data.orderNo')
curl -fsS -H "Authorization: Bearer $MEMBER_TOKEN" "$BASE/api/v1/orders/$ORDER_NO" | jq '.data'
PREPARED=$(curl -fsS -H "Authorization: Bearer $MEMBER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"method":"CARD"}' "$BASE/api/v1/payments/$ORDER_NO/prepare" | jq -c '.data')
PG_TX_ID=$(printf '%s' "$PREPARED" | jq -r '.pgTxId')
printf '%s' "$PREPARED" | jq '{amount,currency,redirectUrl}'
curl -fsS -H "Authorization: Bearer $MEMBER_TOKEN" \
  "$BASE/api/v1/payments/simulator/checkout/$PG_TX_ID" | jq '.data'
curl -fsS -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
  "$BASE/api/v1/payments/simulator/$ORDER_NO/approve" | jq '.data'
curl -fsS -H "Authorization: Bearer $MEMBER_TOKEN" "$BASE/api/v1/payments/$ORDER_NO" | jq '.data'
curl -fsS -H "Authorization: Bearer $MEMBER_TOKEN" "$BASE/api/v1/library" | jq '.data'
curl -fsS -H "Authorization: Bearer $MEMBER_TOKEN" \
  "$BASE/api/v1/downloads/$PRODUCT_CODE/ticket" | jq '.data'
```

주문은 카탈로그의 서버 견적으로 금액을 다시 계산한다. `expectedAmount` 위조는 `PRICE_MISMATCH`로 거절한다. 조회되는 주문의 `lines`에는 주문 시점 상품 ID·이름·판매자·단가·수량이, 주문에는 청구액·통화가 저장된다. `GET /orders/{orderNo}`와 `GET /payments/{orderNo}`의 `status`, `retryable`로 진행 상태를 확인한다. `READY/PENDING` 결제는 처리 중, `FAILED`는 종단 상태이므로 새 주문으로 다시 시도한다. `CANCELING`은 환불 재개 대상이고 `CANCELED`는 완료다. 이벤트 반영 전에는 라이브러리와 주문 상태를 폴링한다.

회원이 직접 환불하려면 `POST /api/v1/payments/{orderNo}/cancel?reason=USER_REFUND`를 회원 토큰으로 호출한다. 시뮬레이터에서 공급자 환불을 재현하려면 운영자 토큰으로 `POST /api/v1/payments/simulator/{orderNo}/refund`를 호출한다. 둘 다 동일한 `RefundFacade`와 `PgClient.cancel` 경로를 거치며, 결제 `CANCELED` 뒤 주문도 `CANCELED`, 라이브러리의 해당 `orderNo` 항목은 제거되고 다운로드 티켓은 `403`이 된다. 반복 환불은 멱등이다.

## 실패 및 격리 재현

새 주문마다 `prepare`를 다시 호출한다. 운영자 토큰으로 `POST /api/v1/payments/simulator/{orderNo}/decline`에 `{"reasonCode":"CARD_DECLINED","reason":"카드 거절"}`을 보내면 결제·주문이 `FAILED`가 된다. `POST .../{orderNo}/timeout`은 `PG_TIMEOUT`으로 같은 종단 상태를 만든다. 승인·거절을 같은 주문에 다시 보내면 중복 결과가 흡수되며, 거절 뒤 승인은 충돌로 거부된다. 일반 회원이 제어 API를 호출하면 `403`, 다른 회원이 `checkout/{pgTxId}` 또는 주문·결제 조회를 하면 `403`이다. 구매자용 결제창 조회는 회원 JWT와 주문 소유권을 모두 확인한다.

## 공급자 어댑터 계약

`PgClient.prepare(orderNo, amount, currency, method)`에는 주문에서 확정된 값만 보낸다. 공급자가 반환한 `pgTxId`를 결제에 저장하고, 승인 사실은 검증된 공급자 콜백 또는 공급자 조회에서만 `PgApproval(orderNo, pgTxId, paidAmount, idempotencyKey)`로 정규화한다. `PaymentService.handleApproval`이 저장된 거래 ID·금액과 대조하고 잠금·멱등 검사를 거친 뒤 `PaymentCompleted`를 기록한다. 결제창 URL이나 브라우저 성공 결과는 이 호출을 만들 권한이 없다. 거절은 `PgDecline(orderNo, pgTxId, reasonCode, reason)`으로 같은 거래 ID를 검증해 `PaymentFailed`를 기록한다. `PgClient.cancel(pgTxId, amount, reason)`은 같은 거래에 여러 번 호출해도 환불이 한 번이어야 한다.

현재 로컬 콜백은 [원문 HMAC·시각 검증 계약](p3-commerce-security.md)을 공급자 인증으로 사용한다. 시뮬레이터는 사전등록 거래 기록을 잠그고 해당 기록의 승인·거절·시간 초과 사실을 만든 뒤 위 결제 유스케이스에 전달한다. 포트원 어댑터(#82)는 실제 공급자의 웹훅 서명과 거래 조회로 이 사실을 검증하고 같은 내부 값으로 전달해야 한다. `prod`에는 별도 `PgClient` 구현·공급자 검증 구성이 필요하며 이 데모 시뮬레이터를 사용하지 않는다.

자동 검증은 `./gradlew :apps:payment:integrationTest`, `./gradlew :e2e:e2eTest`로 실행한다. E2E는 출시 상품을 구매해 다운로드한 뒤 회수하며, 승인·중복·거절·시간 초과·타인 접근과 금액 위조를 검증한다. 일반 프로필과 데모 프로필의 Payment OpenAPI 계약은 각각 `payment.json`, `payment-demo.json`에 고정한다.
