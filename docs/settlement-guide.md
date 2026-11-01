# Settlement·외부 모의 PG 실행 안내

[문서 목록](README.md) · [MVP 기록](settlement-mvp-plan.md) · [책임 분리](settlement-separation-verification.md) · [PortOne 혜택 A·검증](settlement-external-benefit-verification.md) · [정산 모델](domain/models/settlement.md)

Settlement는 결제 완료 주문의 판매자 매출·배분·전액 환불 조정·월 마감과 PG 지급 명세 대사를 담당한다. [환불 원장·월 합계 연결](settlement-refund-ledger-verification.md)을 함께 읽는다.
이번 범위의 수령 완료는 모의 PG 보고와 내부 원장이 일치했다는 의미다. 실제 계좌 입금·판매자 송금은 수행하지 않는다.

## 현재 검증 환경

코드·문서는 로컬에서 편집하고 빌드·테스트·서비스 실행은 `ssh sng` OCI의 전용 작업 디렉터리에서 수행한다. 새 로컬 호스트의 OrbStack은 제거했다. 기존 공유 자료를 보존하고 검증용 DB·포트·컨테이너를 격리한다.

OCI에 동기화한 격리 작업본에서 실행하는 최종 게이트는 다음과 같다. 통합 검사는 실제 MySQL·Kafka와 공급자 계약 픽스처를 사용한다.

```sh
CI=true ./gradlew :apps:settlement:check :apps:settlement:bootJar --console=plain
```

과거 `tools/verification/verify-settlement.py`·`verify-jib.py`의 OrbStack 전체 여정과 Jib 결과는 [MVP 기록](settlement-mvp-plan.md)에 보존한다. 최신 PortOne 거래와 정산 연결을 검증한 결과로 해석하지 않는다. 해당 도구의 스키마 합계 쿼리는 새 비용 테이블을 사용하도록 맞췄으며 이번 책임 분리 작업에서 열한 앱 전체 여정을 다시 실행하지 않았다.

## 직접 기동하는 경우의 설정

| 프로세스 | 환경변수 |
|---|---|
| 외부 모의 PG | MOCK_PG_DB_HOST·PORT·NAME·USER·PASSWORD, MOCK_PG_PAYMENT_KEY·SETTLEMENT_KEY·CONTROL_KEY |
| Payment | MOCK_PG_URI, MOCK_PG_PAYMENT_KEY |
| Settlement | MOCK_PG_URI, MOCK_PG_SETTLEMENT_KEY, CATALOG_BASE_URL, CATALOG_SETTLEMENT_SERVICE_KEY |
| Catalog | CATALOG_SETTLEMENT_SERVICE_KEY |

PG의 세 키는 모두 필수이며 서로 달라야 한다. Payment와 Settlement에는 자기 역할의 키만 제공한다.
제어 키는 검증 운영자·스크립트에만 제공한다. PG DB 자격증명은 PG 프로세스만 사용한다.
PG 비용은 기본 300bps, 신규 결정은 APPROVED다. 검증용으로 MOCK_PG_FEE_RATE_BPS와 MOCK_PG_DECISION=DECLINED를 지정할 수 있다.
변경은 새 결제번호에만 적용되고 기존 결정·비용·명세는 그대로 유지된다.
요율을 변경할 때는 MOCK_PG_FEE_POLICY_ID도 새 버전으로 지정한다. 같은 정책 ID의 다른 요율은 새 결정 생성을 거부한다.
플랫폼 배분 정책은 고정 1000bps이며 PG 비용은 플랫폼 전액 부담이다.

모의 PG를 단독 기동할 때는 세 키와 전용 DB를 준비한 뒤 `./gradlew :simulators:mock-pg:bootRun`을 사용한다.
Compose 앱 구성에는 `mock-pg`와 별도 `pg-mysql`이 포함된다. MOCK_PG_DB_ROOT_PASSWORD와 MOCK_PG_DB_PASSWORD도 명시한다.
이 DB는 업무 MySQL·자격증명·볼륨과 별개이며 PG 제어 경로는 Gateway로 라우팅하지 않는다.

## PortOne TEST 원승인 조회

Settlement JAR에 다음 환경변수를 명시하면 모의 PG 대신 PortOne 원승인 읽기 어댑터를 선택한다. 기본 공급자는 SIMULATED이고 기존 Compose의 기본 설정도 모의 PG다. 컨테이너에서 이 모드를 사용할 때는 같은 변수들을 전용 Compose override의 `settlement.environment`에 명시한다. 실제 런타임 갱신은 이번 검증 범위에 포함하지 않았다.

| 환경변수 | 값·역할 |
|---|---|
| SETTLEMENT_PG_PROVIDER | PORTONE_TEST |
| PORTONE_API_BASE_URL | 기본 https://api.portone.io, 격리 HTTP 계약 검증에서는 루프백 |
| PORTONE_API_SECRET | PortOne V2 API Secret. Settlement의 직접 읽기 조회에 사용 |
| PORTONE_STORE_ID·PORTONE_CHANNEL_KEY | 승인 거래와 대조할 상점·TOSSPAY_V2 TEST 채널 |
| CATALOG_BASE_URL·CATALOG_SETTLEMENT_SERVICE_KEY | 불변 판매자 확인에 사용 |

Mock PG 키·웹훅 Secret·환불 실행 키는 이 읽기 경로에 필요하지 않다. Secret을 설정 출력에 노출하지 않는다. 불완전한 설정·지원하지 않는 공급자는 시작 시 거절한다. 어댑터는 GET만 수행한다. PAID·취소액 0인 원승인 외에, 전액 성공 취소 근거가 검증되는 CANCELLED 거래의 원승인도 지연 매출로 접수할 수 있다. 부분 취소·미확정 취소는 이 전액 환불 경로의 대상이 아니다.

확인된 토스 부담 혜택은 주문 금액 기준으로 배분하고 총액·실결제·혜택과 채널/정책 근거를 `PgFunding`에 보존한다. ADMIN 매출·거래 재조회에서 `funding`을 확인한다. 비용 자료는 만들지 않으며 미확정 상태에서도 검증된 판매자 금액은 마감한다. 미지원 혜택·잘못된 거래는 배분하지 않고 기존 재시도/DLT 절차로 원인을 확인한다.

이 모드에서 기존 `/pg-receipts`의 신규 명세 수집은 출처 미연동 503이다. TEST의 빈 정산 결과를 0원 수수료·모의 수령으로 만들지 않는다. 기존에 저장된 명세 조회·재대사는 내부 저장 사실을 사용한다. 별도 설정하는 토스페이 정산 API 수집 경로는 아래 절을 따른다. [기존 연결의 정확한 계약·검증 범위](settlement-external-benefit-verification.md)를 따른다.

## 토스페이 정산 자료 자동 수집

[결정 43](decisions.md#tosspay-statement-api-source)의 A에 따라 기존 모의 PG 명세와 구분한 `/api/v1/settlements/pg-statements` 경로를 제공한다. [원본·부호·대사 상태와 검증 범위](settlement-pg-evidence-verification.md)를 먼저 확인한다. 실제 스택 설정은 갱신하지 않았다.

| 환경변수 | 역할 |
|---|---|
| SETTLEMENT_TOSSPAY_ENABLED | 기본 false. true일 때 명시적인 토스페이 정산 조회 키와 범위를 검증해 사용 |
| TOSSPAY_API_BASE_URL | 기본 https://pay.toss.im. 공식 주소의 HTTPS 또는 격리 계약 검증용 루프백만 허용 |
| TOSSPAY_API_KEY | 해당 가맹점의 sk_test_ / sk_live_ 키. PortOne Secret과 별개이며 설정 문자열에서 숨김 |
| TOSSPAY_MERCHANT_ID·TOSSPAY_ENVIRONMENT | 그 키에 대응하는 가맹점과 TEST/LIVE 범위. 자동으로 LIVE로 전환하지 않음 |
| TOSSPAY_PORTONE_STORE_ID·TOSSPAY_PORTONE_CHANNEL_KEY | 내부 승인과 대조할 상점/채널 범위 |
| TOSSPAY_IDENTITY_MAPPING_VERIFIED | 기본 false. 실제 자료로 pgTxId/payToken·paymentId/PG orderNo 대응을 확인한 경우에만 true. TEST에는 사용 불가 |
| TOSSPAY_SCHEDULED_ENABLED | 기본 true. 조회 enabled와 모두 true일 때 주기 재수집 |

`stove.settlement.tosspay.max-pages`는 기본 100, `connect-timeout`/`read-timeout`은 2초/10초다. `poll-delay`는 1시간, `lookback-days`는 7일(허용 1–31일)이며 config에서 조립한다. KST 10시 전에는 자동 수집을 건너뛰고 이후 오늘 DUE 및 lookback의 DUE/SETTLE을 수집한다. 중간 실패는 다음 주기에 재시도한다. 이전 날짜도 공급자 범위 1년 안에서 ADMIN API로 다시 수집할 수 있다.

ADMIN POST `/api/v1/settlements/pg-statements`에 `{"dateType":"DUE","baseDate":"2026-10-01"}` 같은 조회 기준만 전달한다. GET 목록/상세·항목/원본 페이지와 `/{id}/reconcile` 재대사를 제공한다. 부분/중간 페이지·합계 불일치는 저장하지 않으며 중복 조회/동시 수집과 DUE/SETTLE의 중복 거래는 금액을 중복 확정하지 않는다. 수집본 목록은 관측 이력이므로 모든 수집본 합계를 다시 더하지 않는다.

현재 TEST의 빈 조회는 EMPTY_TEST로만 보관하며 비용 0원·은행 입금 완료가 아니다. LIVE 명세를 현재 PORTONE_TEST 원승인에 연결해 비용을 확정하지 않는다. 현재 LIVE 승인 접수와 실제 키 대응은 미검증이므로 이 단위의 대사 일치 계산은 계약 픽스처와 구분한다. 기존 월 PG 비용 요약은 미확정 상태를 유지하고, 직접 보고된 수수료/VAT·반환·현금 합계는 새 명세/항목 조회에서 확인한다. PG 비용의 월 귀속/기존 요약 통합·카드 차액정산 등 추가 출처는 후속이다.

## 매출·마감·PG 수령

CREATOR는 JWT sub에 해당하는 세 GET을 사용한다.

```sh
curl -H "Authorization: Bearer $CREATOR_TOKEN" "$GATEWAY/api/v1/settlements/me/sales"
curl -H "Authorization: Bearer $CREATOR_TOKEN" "$GATEWAY/api/v1/settlements/me/months"
curl -H "Authorization: Bearer $CREATOR_TOKEN" "$GATEWAY/api/v1/settlements/me/refunds"
```

ADMIN은 `/sales`, `/months`의 sellerSubject·month 필터로 조회하고
`POST /api/v1/settlements/sellers/{sellerSubject}/months/{yyyy-MM}/close`로 지난 KST 월을 마감한다.
현재·미래 월은 409다. 마감 후 도착한 매출은 현재 월에 반영하고 원래 매출월을 보존한다.
CLOSED는 금액 마감이며 판매자 지급 완료가 아니다. 판매자 금액이 검증됐으면 PG 비용이 미확정이어도 마감한다. PG 비용·대사는 별도로 수행하며 뒤늦은 비용 확인이 판매자 금액이나 최초 마감 시각을 바꾸지 않는다.

ADMIN 매출의 `pgCostStatus`가 UNCONFIRMED이면 `pgCost`·`platformNetAmount`는 NULL이다. 월 조회는 CONFIRMED·UNCONFIRMED·PARTIAL, `confirmedPgFeeAmount` 부분합과 `pendingPgCostCount`를 제공한다. 일부 미확정이면 전체 `pgFeeAmount`·`platformNetAmount`는 NULL이며 확인된 0원과 구분한다.

`POST /api/v1/settlements/pg-transactions/{paymentId}/refresh`는 ADMIN이 이미 접수한 거래의 PG 근거를 다시 조회한다. 본문으로 비용을 입력하지 않는다. 공급자 GET과 거래 동일성 검증 뒤 확인된 비용을 별도로 저장하며 PG 승인·취소·송금은 수행하지 않는다. 기존 비용이 다르면 409로 보존하고 원자료를 조사한다. 비용이 계속 없으면 미확정으로 유지한다. Gateway의 기존 ADMIN `/api/v1/settlements/**` 경로를 사용한다.

검증 운영자는 외부 PG의 `POST /test-support/v1/settlement-runs`에 제어 키와 UUID requestId를 보내 회차를 만든다.
반환된 providerSettlementId를 ADMIN이 `POST /api/v1/settlements/pg-receipts` 본문에 전달한다.
플랫폼은 이미 있는 명세를 GET으로 읽으며 지급 회차를 생성하지 않는다. 운영자가 금액·수령 상태를 입력하지 않는다.

| 수령 상태 | 의미·후속 동작 |
|---|---|
| REPORTED_PAID | 공급자 지급 보고 접수. 미도착 매출·비용 확인을 기다림 |
| MISMATCH | 도착한 내부 거래의 연결·금액·비용 불일치. 원본을 대조하고 근거 보존 |
| SIMULATED_RECEIVED | 모든 거래가 일치해 모의 수령 확정. 최초 시각 유지 |

명세 조회는 ADMIN의 `/pg-receipts`, `/pg-receipts/{providerSettlementId}`다.
미도착 매출이 반영되거나 별도로 비용을 확인하면 `POST /pg-receipts/{providerSettlementId}/reconcile`로 저장한 명세를 재대사한다. 응답 항목에서 PG_COST_PENDING·DIFFERENT_TRANSACTION·DIFFERENT_COST를 구분한다.
재대사는 PG·Catalog·Payment가 중단되어도 가능하다. 신규 매출은 PG·Catalog 근거가 필요하다.
Kafka 재시도 소진으로 DLT에 간 매출은 원인 해소 후 원래 키·본문·이벤트 ID·종류를 유지해 재전달한다.
같은 입력·명세·대사 재시도는 금액을 중복 반영하지 않는다.

## 전액 환불 조정

Payment의 최초 `RefundPaymentConfirmed`를 독립 PG GET으로 대조해 원래 판매자·플랫폼 배분을 전액 상계한다. 금융 확인 월이 OPEN이면 해당 월, CLOSED이면 현재 OPEN 월에 저장한다. 원매출·기존 마감은 그대로 두고 환불 조정 원장을 추가한다. 같은 이벤트는 최초 반영 월·정책·금액을 유지한다. 늦은 실행 성공 보강 이벤트는 새 차감으로 처리하지 않는다.

월 응답의 기존 금액은 매출 총합이다. `refundAmount`·`refundedPlatformFeeAmount`·`refundedSellerPayoutAmount`·`refundCount`는 양수 환불 합계이며 `netPaidAmount`·`netPlatformFeeAmount`·`netSellerPayoutAmount`가 차감 후 순액이다. 환불만 반영한 월은 순액이 음수일 수 있다. 자동 이월·송금·계좌 회수는 별도 정책이다.

ADMIN의 GET `/api/v1/settlements/refunds`는 판매자/반영 월 필터 목록, `/{refundId}`는 최초 금융 입력·정책·취소별 직접 조회 근거를 제공한다. CREATOR 목록에는 구매자·결제/PG 실행 정보를 제공하지 않는다. PG 비용 반환 근거가 아직 없으므로 환불이 있는 월의 전체 PG 비용/플랫폼 잔여액은 NULL이다. 매출 확정 비용 부분합과 `salePgFeeAmount`는 별도로 확인한다.

원매출이 없으면 기존 Kafka 재시도·DLT에 남고 Inbox·환불·월 합계를 저장하지 않는다. 원매출을 복구한 뒤 원래 금융 확인 이벤트를 재처리한다. SIMULATED의 환불 읽기는 미연동 503이며 임의 취소 자료를 만들지 않는다. 실제 제공사/공유 런타임 적용과 정확한 검증 범위는 [원장 연결 기록](settlement-refund-ledger-verification.md)을 따른다.

## 기존 내장 PG 이관

아래 도구는 오프라인 운영 도구다. 구 Payment의 처리·내장 PG 쓰기를 중지한 뒤 실행한다.
기존 원장·Outbox·Inbox·소비 그룹·DLT를 보존하고 대상과 시작점을 확인한다. 공유 스택은 검증 스크립트가 이관하지 않는다.

```sh
export SOURCE_MYSQL_PASSWORD='source-password'
export TARGET_MYSQL_PASSWORD='target-password'
python3 tools/migrations/mock-pg/migrate.py \
  --source-container OLD_PAYMENT_MYSQL --source-db stove_payment \
  --target-container NEW_PG_MYSQL --target-db mock_pg
```

소스는 V11의 simulated_payment_charge만 읽는다. 원래 결제번호·주문번호·거래 ID·결정·시각·사유를 보존하고 비용은 미산정으로 둔다.
동일 행은 재실행 시 건너뛰고 다른 값은 충돌로 중단한다. 새 PG 원장 저장과 소스 불변 여부를 대조한다.
미산정 비용 거래는 새 정산 명세에서 제외한다. 현재 요율로 소급 계산하지 않는다.
Payment를 처리 비활성 상태로 HTTP에 연결하고 원래 키의 조회·PENDING 복구·미발행 결과를 대조한 뒤 처리기를 재개한다.
구 내장 PG와 외부 PG에 동시에 쓰지 않는다.
