# G2 할인 환불 A안 — Payment 내부 계약 구현과 검증

2026-10-30. 사용자는 [결정 35](decisions.md#provider-discount-full-refund)의 **A안: 제공사 전액 취소 계약에 따른 반환, 할인 혜택 재사용은 제공사 정책**을 선택했다. 사용 포인트 반환 근거를 보존하고 플랫폼 별도 보전은 추가하지 않는다. [실행 계획](g2-refund-journey-plan.md)의 P1에 대한 Payment 내부 구현이다. 고객 조회 방식 P2와 실제 할인 거래의 PG 취소 전체 여정은 별도 범위다.

**최종 코드 검증:** OCI Payment `check`·`bootJar`가 통과했다. 단위·아키텍처 373·일반 통합 244·과거 이행 9의 총 **626건**, 실패·오류·건너뜀 0이다. 실제 할인 거래의 PG 취소와 고객/ADMIN 전체 여정의 완료를 뜻하지 않는다.

## 문제와 변경

원래 주문 9,000원·PG 실결제 8,976원·할인 24원을 승인하는 계약은 있었지만, 환불의 사전 조회·완료 조회·실패 조회는 실결제=주문 금액·할인=0을 요구했다. 그 결과 정상 할인 구매가 환불 금액 대조를 통과하지 못했다. 취소 응답의 적립형 포인트 금액도 0이 아니면 거절하거나 보존하지 않았다.

- `PgRefundLookupRequest.from(Payment)`가 원승인의 저장된 PG 실결제·할인 스냅샷을 함께 전달한다. 취소 서비스·조회 조율·도메인의 근거 대조가 같은 구매 입력을 사용한다.
- `PortOneRefundAmounts`는 공급자 원구매의 총액·실결제·할인을 검증하고 저장한 원승인 구성과 각각 비교한다. 합계만 같은 다른 구성을 허용하지 않는다. 원승인 구성이 NULL인 건에 새 할인 근거를 임의로 적용하거나 값을 채우지 않는다. 기존 무할인 검증의 제한된 경로는 유지한다.
- `easyPayDiscountAmount`는 제공사가 보고한 적립형 포인트 금액으로 별도 보존한다. 직접 성공·후속 조회의 성공 취소별 근거, REQUESTED/FAILED 응답 영수증에 연결한다. 실패/접수 응답의 해당 값은 실제 반환 완료 근거가 아니다.
- 누락된 포인트 금액은 NULL로 유지하고 승인 할인 전체를 반환 포인트로 간주하지 않는다. 음수·정수가 아닌 값·승인 할인 한도 초과와 여러 성공 취소의 보고 포인트 합계 초과를 거절한다. 같은 취소 ID·실행의 확정 금액 구성이 바뀌면 기존 근거를 덮어쓰지 않는다.
- 원구매·금융 상태·고정 PG 요청 본문·멱등 키·최초 준비와 3시간 창, 실제 정지 선행·확정 실패/복구·명시적 후속 시도를 유지한다. 외부 이벤트·Order/Gateway 고객 API·Settlement는 변경하지 않았다.

주요 구현은 [구매 입력](../apps/payment/src/main/java/com/stove/payment/core/domain/PgRefundLookupRequest.java), [공급자 금액 대조](../apps/payment/src/main/java/com/stove/payment/adapter/pg/PortOneRefundAmounts.java), [취소 실행](../apps/payment/src/main/java/com/stove/payment/adapter/pg/PortOneRefundCancellationGateway.java), [후속 조회](../apps/payment/src/main/java/com/stove/payment/adapter/pg/PortOneRefundPaymentLookup.java), [내구 실행·근거](../apps/payment/src/main/java/com/stove/payment/core/domain/RefundExecution.java)를 따른다.

## 공급자 금액의 의미와 검증 한계

| 항목 | 이번 처리 |
|---|---|
| 원주문 총액 | 원래 구매 근거로 보존한다. 고객에게 지급할 현금이라는 의미로 바꾸지 않는다. |
| 승인 실결제·할인 | 저장된 두 값을 원거래와 함께 대조한다. 할인 구성이나 비용 부담을 새로 추정하지 않는다. |
| PG 취소 요청 amount·currentCancellableAmount | 기존의 원주문 총액·미취소 잔액 검사를 유지한다. 요청 본문 형식이나 이미 준비한 요청을 변경하지 않는다. |
| 전액 취소 조회 | 기존 CANCELLED·총 취소액·서로 다른 성공 취소의 합계·원구매 대조를 유지하면서 할인 승인 구성을 검증한다. 임의 비율 환산이나 실결제액과 총액 중 더 유리한 숫자 선택을 추가하지 않는다. |
| 보고된 포인트 금액 | 성공/접수/실패 상태와 출처를 구분해 보존한다. 누락은 미확인이고 0원으로 채우지 않는다. 승인 할인액과 동일하다고 가정하지 않는다. |
| 고객 결제수단 반환·할인 혜택 재사용 | 이번 내부 필드로 현금 지급 완료·쿠폰 복원·재사용 가능 여부를 선언하지 않는다. 제공사 근거와 선택한 A안을 바탕으로 P2의 안내 계약을 별도로 정한다. |

2026-10-30 대조한 [PortOne 현행 OpenAPI](https://developers.portone.io/api/schema/v2/openapi.json)는 할인과 실결제, 취소 총액과 포인트 반환 필드를 구분한다. [프로모션 취소 가이드](https://developers.portone.io/opi/ko/extra/promotion/refund)는 프로모션 적용 전 주문 금액으로 취소를 요청하는 사례를 설명한다. 이 프로모션 예시를 현재 토스페이 채널의 모든 할인·포인트 조합에 대한 실측 근거로 확대하지 않는다. [토스페이 환불](https://docs-pay.toss.im/reference/common/refunds)·[상태 조회](https://docs-pay.toss.im/reference/common/status)의 원천 필드와 PortOne 응답의 채널별 대응은 추가 실제 TEST 여정에서 확인할 관측 항목이다. 직접 토스 API를 호출하는 구현은 추가하지 않았다.

이번 HTTP 픽스처는 원구매 총액과 전액 취소 합계가 일치하는 기존 계약에서 할인/포인트 구성의 수용·거절과 내구 저장을 증명한다. 실제 할인 거래의 취소 응답·잔액·결제수단별 반환을 새로 관측한 결과가 아니다. 실제 제공사의 관측값이 이 계약과 다르면 미확정/거절을 유지하며 그 근거를 확인한 뒤 수정한다.

## 저장과 이행

[V33](../apps/payment/src/main/resources/db/migration/V33__refund_provider_point_amounts.sql)은 환불 실행의 접수/실패 영수증·직접 성공 응답·성공 취소별 근거에 nullable 포인트 컬럼 세 개를 추가한다. DB는 음수·취소 총액 초과와 연결된 영수증/성공 상태가 없는 값을 막고, 도메인은 원승인 할인 한도도 대조한다.

과거 미기록 값은 NULL로 남는다. 원승인 금액·환불 근거·원래 준비 시각·요청 본문을 수정하거나 과거 기록을 0원으로 채우지 않는다. V32 자료를 별도 검증 스키마에 복사한 뒤 V33을 적용해 원자료와 NULL 보존을 검사한다. 특정 과거 개발 주문의 호환 정책을 추가한 것은 아니다.

## TDD와 검증

모든 빌드·테스트는 `ssh sng`의 `/tmp/esd-g2-discount-refund-20261030.xPdFEx6x/work`에서 실행한다. 로컬은 코드·문서 편집과 결과 수집에만 사용한다. 원자료는 Git 제외 `runs/g2-discount-refund-2f0cxnuo/`다.

- 원승인 구성 전달을 위한 최소 입력 계약을 먼저 추가하고 할인 취소·완료 조회·실패 조회의 **동작 Red 3건**을 확인했다. 이후 집중 40건이 통과했다.
- 반환 포인트를 보관할 최소 타입을 추가한 뒤 직접 성공/접수·실패 영수증/조회에서의 누락·거절 **동작 Red 4건**을 확인했다. 컴파일 실패를 업무 Red로 집계하지 않았다.
- 도메인·HTTP 금액 방어와 전체 단위·아키텍처 373건, 통합 컴파일이 통과했다. 정상 할인·다른 원승인 구성·선택적 포인트·과대/음수/오류 타입·불변성·원구매 보존을 포함한다.
- 실제 MySQL 집중 4건은 직접 성공의 포인트 저장·중복 수렴, 응답 유실 뒤 조회 저장, 실패 이력과 명시적 후속 시도, 실제 flush 뒤 포인트 근거·완료 Outbox의 전체 롤백을 확인했다. HTTP 공급자는 루프백 픽스처다.
- 최종 검사는 `CI=true`에서 `:apps:payment:check :apps:payment:bootJar`를 실행하며 단위·일반 통합·과거 이행을 모두 포함한다. 변경하지 않은 열한 앱 전체 검사를 반복하지 않는다.

최종 실행은 다음과 같다. 경과 시간은 382.70초다. 집중 검사·Red·중간 Green을 최종 건수와 중복 합산하지 않는다.

```bash
./gradlew --offline --max-workers=2 -Pstove.infraTest.maxParallel=1 --console=plain \
  :apps:payment:check :apps:payment:bootJar
```

| 최종 태스크 | 사례 | 실패·오류·건너뜀 |
|---|---:|---:|
| test | 373 | 0 |
| integrationTest | 244 | 0 |
| migrationTest | 9 | 0 |
| 합계 | 626 | 0 |

실행 로그·JUnit XML과 단계별 결과는 위 runs 경로의 `collected/`에 보존한다. V33의 새 이행 검사도 최종 migrationTest에 포함했다.

## 소스·산출물·공유 환경 보존

- 비밀 설정·Git 이력·문서를 제외한 소스 1,137개의 해시가 로컬 스냅샷·실제 OCI 작업본과 일치한다. Payment JAR에 새 금액 검증 클래스가 포함되고 테스트 Fixture JAR는 포함되지 않음을 확인했다. JAR SHA-256과 대조 결과는 `collected/final-integrity.json`에 있다.
- 실행 전후 기존 컨테이너 23개의 ID·시작 시각·재시작 횟수·실행/건강 상태·실제 mount 구성과 볼륨 57개가 같다. 이번 Testcontainers 자원은 남지 않았으며 기존 업무 자료와 검증 경로를 보존했다.
- 최초 보존 대조는 두 컨테이너의 Docker inspect mount 배열 순서 차이로 실패했다. 실제 항목과 나머지 상태가 같음을 읽기 전용으로 확인하고, 원본 결과를 `initial-after.json`·`initial-final-integrity.json`에 보존한 뒤 mount 목록만 정규화해 재대조했다. 업무 코드·인프라를 바꾸거나 테스트를 다시 실행해 숨긴 실패가 아니다.
- 공급자 HTTP는 격리 픽스처를 사용했다. 실제 PortOne 승인/취소·기존 성공 거래의 금융 상태·채널·웹훅·공유 서비스 배포는 변경하지 않았다. 추가 실제 금융 동작은 이 코드 검사에 포함하지 않는다.

## 후속

P1 완료 당시 다음 논의였던 P2는 이후 사용자가 A안을 선택해 [고객/관리자 환불 금액 상세](g2-refund-amount-details-implementation.md)로 구현·검증했다. 원주문 금액과 승인/확인 반환 구성을 구분하고 미확인은 NULL로 제공한다. P1 자체의 검증 범위·626건과 후속 결과를 구분한다.

실제 할인 TEST 환불과 최신 고객/ADMIN·License·Download 전체 여정, 지급 확인 누락 복구·재구매, G3 정산 조정은 남아 있다. 기존 성공 거래의 승인·환불 스크립트는 반복하지 않았고 실제 추가 금융 동작도 수행하지 않았다.

2026-10-30 후속으로 사용자가 특정 거래 실행을 승인해 [실제 TOSSPAY_V2 TEST 수동 환불·이용권·다운로드](g2-refund-role-journey-verification.md)를 완료했다. 이번 거래의 취소 총액 9,000원·보고 포인트 24원과 고객/관리자 상세·NULL, 정지 선행·새 다운로드 거절·기존 URL 자연 만료를 관측했다. 이 문서의 내부 코드 검증 당시 범위·사례 수는 보존하며 실제 여정과 합산하지 않는다. 모든 할인/포인트 조합·실제 계정 잔액이나 혜택 재사용의 실측으로 확대하지 않는다.
