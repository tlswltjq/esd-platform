# G2 환불 금액 상세 A안 — 이벤트·조회 사본·고객/관리자 API

2026-10-30. 사용자가 [결정 36](decisions.md#refund-amount-details)의 **A안: 환불 상세 한 곳에서 주문·승인 금액과 확인된 반환 구성을 함께 조회**를 선택했다. P1 Payment 할인 환불 이후의 P2 구현이다. 기존 amount의 의미와 전액 취소 완료 기준을 유지한다.

**최종 검증:** OCI의 공통 Kafka·Order·Payment·Gateway 관련 게이트 **1,526건**, 실패·오류·건너뜀 0과 Order·Payment bootJar가 통과했다. 실제 할인 TEST 취소·최신 Auth 전체 여정은 이번에 실행하지 않았다.

## 고객·관리자가 확인하는 금액

기존 환불 응답의 amount는 원래 주문 금액이다. 여기에 `amountDetails`를 추가했다. 고객 본인 조회와 ADMIN 조회·목록은 같은 저장 근거를 사용하며 조회 시 Payment·PG HTTP를 호출하지 않는다.

| 필드 | 의미 |
|---|---|
| orderAmount | 원래 주문 금액. 기존 amount와 같다. |
| pgPaidAmount·pgDiscountAmount | 결제 당시 제공사가 확인한 실결제·할인. 반환 완료 금액이나 반환 포인트가 아니다. |
| confirmedCancellationAmount | 성공 취소 근거의 총액. 고객에게 입금된 현금으로 해석하지 않는다. |
| confirmedPointRefundAmount | 성공 취소 전체에 직접 보고된 포인트의 합계. 하나라도 누락되면 NULL이다. 명시된 0원과 구분한다. |
| confirmedPaymentMethodRefundAmount | 직접 확인한 결제수단 반환액을 위한 항목. 현재 입력 계약에 직접 근거가 없어 NULL이다. 승인 실결제액이나 취소 총액에서 계산하지 않는다. |
| reportedAt·source | 반환 금액 상세가 저장·보완된 이벤트 시각과 PG_RESPONSE / PG_QUERY_UNATTRIBUTED 출처. 실제 계좌 반영 시각을 뜻하지 않는다. 승인 금액만 확인된 상태에서는 두 값도 NULL일 수 있다. |
| unconfirmedFields | 미확인인 금액 필드명. 금액을 0원으로 채우거나 COMPLETED를 보류하는 사유가 아니다. |

검증 예시는 주문 10,000원·승인 실결제 9,976원·승인 할인 24원·보고 포인트 12원이다. 완료 응답은 기존 `amount=10000`, `status=COMPLETED`를 유지하고 취소 총액 10,000원·보고 포인트 12원을 별도 제공한다. 결제수단 반환액은 NULL이다. **이 숫자는 계약 픽스처이며 기존 실제 9,000원 거래의 취소 실측값이 아니다.**

주요 경계는 [금액 상세 응답](../apps/order/src/main/java/com/stove/order/api/controller/dto/RefundAmountDetailsResponse.java), [고객 조회](../apps/order/src/main/java/com/stove/order/core/service/ReadRefund.java), [관리자 조회](../apps/order/src/main/java/com/stove/order/core/service/ReadRefundReviews.java)다. 본인·ADMIN 권한, 타인/비로그인 거절과 no-store를 유지한다. Gateway는 새 값을 재계산하지 않고 NULL을 포함해 전달한다.

## Payment 원본과 Order 사본

- `OrderPaymentCompletedEvent`에 선택적 PG 실결제·할인 쌍을 추가했다. Payment가 저장한 승인 근거를 발행하고 Order는 PAID 결과와 함께 저장한다. 한쪽만 있거나 합계가 원주문과 다른 쌍은 거절한다.
- `RefundPaymentConfirmedEvent`·`RefundExecutionSucceededEvent`에 선택적 `RefundAmountsSnapshot`을 추가했다. 원승인 쌍, 성공 취소별 ID·총액·선택적 포인트, 제공사 취소 시각을 담는다. [Payment 변환](../apps/payment/src/main/java/com/stove/payment/core/service/CreateRefundAmountsSnapshot.java)은 저장된 성공 근거만 사용하며 반환 현금을 계산하지 않는다. 접수·실패 응답의 금액을 성공 반환으로 전달하지 않는다.
- Order의 [외부 금액 Mapper](../apps/order/src/main/java/com/stove/order/api/listener/RefundAmountMapper.java)와 [내부 입력](../apps/order/src/main/java/com/stove/order/core/domain/ReportedRefundAmounts.java)이 금액 쌍·원주문 총액·서로 다른 취소 ID·포인트 범위·시각을 검증한다. 봉투·본문·키 및 원래 구매·환불·시도 대조도 유지한다. 서비스는 내부 입력으로 Inbox·잠금·업무 변경을 같은 트랜잭션에서 조율한다.
- [RefundAmountProjection](../apps/order/src/main/java/com/stove/order/core/domain/RefundAmountProjection.java)은 금융 완료와 별도의 읽기 사본이다. 먼저 도착한 정상 근거를 보존하고, 후속 정상 이벤트가 미확인 값만 보완할 수 있다. 이미 확인한 총액·취소 ID 집합·승인 구성·포인트·제공사 취소 시각의 모순은 거절한다. 이미 확인한 값을 NULL로 지우거나 최신 근거 시각을 과거로 옮기지 않는다.
- 금액 사본 보완으로 최초 completedAt·금융 확인 이벤트/출처·원래 PAID를 덮어쓰지 않는다. 원승인 결과가 미기록인 Order는 환불 사본의 확인된 승인 구성을 조회에 사용할 수 있지만, 원승인 이벤트를 받은 것처럼 Order의 결과를 수정하지 않는다.
- 선택 필드가 없는 이전 이벤트는 기존 금융 계약에 따라 처리하고 상세는 미확인으로 남긴다. 상세를 재생성하기 위한 과거 Outbox 변경·강제 재발행·PG 재조회 작업은 추가하지 않았다. Order의 NULL 보완 수용은 Payment 원본의 누락 정보가 자동 생성된다는 보장이 아니다.

공통 payload DTO는 원시 계약이며 모듈 내부 검증 모델로 사용하지 않는다. 상세가 있는 이벤트는 상세 지문까지 중복 대조한다. 상세가 없는 기존 금융 확인의 지문 형식은 유지한다. 늦은 실행 성공의 귀속과 금융 완료를 분리하는 기존 계약도 유지한다.

## V27과 과거 자료

[Order V27](../apps/order/src/main/resources/db/migration/V27__refund_amount_details.sql)은 원승인 금액 두 컬럼과 환불 금액 사본 여덟 컬럼을 nullable로 추가한다. 과거 값을 주문 총액·0원으로 채우지 않는다. DB는 승인 금액 쌍/합계·상태와 반환 사본의 출처·시각·포인트 범위를 검사한다. 원주문과 환불의 총액 대조, 취소 ID 집합 대조는 도메인에서도 수행한다.

V26의 구매·완료 환불을 준비한 뒤 최신 이행을 적용해 원래 자료 전체가 같고 새 열만 NULL인지 확인한다. 기존 역사 이행 검사의 행 비교는 **명시한 새 nullable 금액 열에 한해서 NULL 추가를 허용**하고 나머지 값·행 수·순서는 그대로 대조한다. 특정 개발 주문의 보존/호환 정책을 추가하거나 과거 금융 사실을 재작성한 작업이 아니다. Payment에는 P1의 V33 외 새 이행이 없다.

## TDD·관련 통합·최종 게이트

모든 빌드·테스트는 `ssh sng`의 `/tmp/esd-g2-refund-amount-details-20261030.su1iuC3O/work`에서 실행했다. 로컬에서는 편집·소스 묶음 작성·원자료 수집만 했다. 원자료는 Git 제외 `runs/g2-refund-amount-details-kz_dg94j/`다.

1. 최소 타입/컴파일 계약을 준비한 뒤 기존 구현이 승인 구성·환불 상세를 버리고 잘못된 상세도 무시하는 **동작 Red 5건**을 확인했다. 초기 7건 중 다른 2건은 기존 계약의 통과를 확인했다. 컴파일 실패를 업무 Red로 세지 않았다.
2. Order 사본·조회·응답과 Payment 발행을 구현한 뒤 단위·아키텍처를 검사했다. Payment 내부 변환 보조 클래스의 이름이 기존 서비스 명명 규칙에 맞지 않아 실패한 1건은 `CreateRefundAmountsSnapshot`으로 수정했다. 규칙을 완화하지 않았고 이후 전체 Payment 373건이 통과했다.
3. 공통 이벤트 224·Order 359·Payment 373건의 단위·아키텍처 및 통합 컴파일이 통과했다. 누락/0원·부분 포인트·모순 근거·ID/합계/시각·최초 완료 보존·역순 수신을 포함한다.
4. 집중 검사는 Order 5건·Payment 2건·Gateway 122건이 통과했다. 실제 MySQL 고객/관리자 HTTP, Kafka 두 순서·중복, 실제 flush 후 완료/사본/Inbox 롤백과 같은 입력 재처리, Payment 승인/취소 Outbox의 금액 payload를 확인했다. Gateway는 역할·헤더·NULL 전달을 검증한다. 공급자 HTTP는 루프백 픽스처이며 실제 PortOne 금융 동작이 아니다.
5. OpenAPI 갱신 단계에서 이전 명세를 보존하고 구조를 비교했다. 기존 12개 경로·14개 업무 연산·기존 필드/보안은 같다. `RefundAmountDetailsResponse`와 두 환불 응답의 `amountDetails`만 추가했다. 최종 검사는 명세 갱신 플래그 없이 실행한다.

최종 실행은 JDK Temurin 21.0.12·Gradle 8.14.3, `CI=true`에서 아래 명령으로 수행했다. 경과 시간은 **662.92초**다.

```bash
./gradlew --offline --max-workers=2 -Pstove.infraTest.maxParallel=1 --console=plain \
  :common:kafka:test :apps:order:check :apps:payment:check :apps:gateway:check \
  :apps:order:bootJar :apps:payment:bootJar
```

| 최종 태스크 | 사례 | 실패·오류·건너뜀 |
|---|---:|---:|
| common:kafka:test | 224 | 0 |
| apps:order:test | 359 | 0 |
| apps:order:integrationTest | 180 | 0 |
| apps:order:migrationTest | 13 | 0 |
| apps:payment:test | 373 | 0 |
| apps:payment:integrationTest | 244 | 0 |
| apps:payment:migrationTest | 9 | 0 |
| apps:gateway:test | 122 | 0 |
| apps:gateway:integrationTest | 2 | 0 |
| 합계 | **1,526** | **0** |

최종 로그·JUnit XML·태스크/필터·실행 환경은 위 runs 경로의 `collected/`에 보존한다.

Red·중간 Green·집중 실행을 최종 사례 수와 중복 합산하지 않는다. 일반 통합과 과거 이행은 분리해 반복했으며 최종 Order·Payment `check`에는 둘 다 포함했다. 변경하지 않은 다른 앱 전체 검증은 반복하지 않았다.

## 소스·산출물·공유 환경

- 비밀 설정·Git 이력·문서를 제외한 소스 **1,149개**의 최종 해시가 로컬 작업본·전송 스냅샷·OCI 실행본과 일치한다. 최종 manifest SHA-256은 `63a8f1cd7443c03a16913301e412eb7981bd434c5ce91124d72075ca54b9a642`다.
- Order·Payment JAR에서 새 응답/조회 사본·이벤트 변환 클래스와 테스트 Fixture JAR 제외를 확인했다. 각 JAR SHA-256과 전체 대조는 `collected/final-integrity.json`에 있다. 명명 규칙 수정 전 보조 클래스는 실행본과 JAR에 남지 않았다.
- 실행 전후 기존 컨테이너 **23개**, 볼륨 **57개**가 같다. 컨테이너 ID·시작 시각·재시작·실행/건강 상태·실제 mount 구성을 대조했고 이번 Testcontainers 자원은 남지 않았다. Docker inspect의 mount 목록은 항목 순서만 정규화해 비교하며 원본 before/after JSON도 보존한다.
- 공유 컨테이너·DB·볼륨·검증 자료를 삭제하거나 기존 서비스를 다시 기동하지 않았다. 소스·로그·JUnit·검증 JAR는 실행별 임시 경로에 보존했다.

## 남은 범위

추가 실제 PortOne 승인·취소, 기존 성공 거래의 금융 상태·채널·웹훅, 공유 서비스 배포를 변경하지 않았다. 이 결과는 계약·DB/Kafka·역할별 HTTP 검증이다. 실제 Auth 로그인부터 시작하는 최신 할인 TEST 환불·License 정지/복구·Download 차단 전체 여정의 완료가 아니다.

다음은 G2 실행 계획의 구체적인 거래·현재 자격·금액·금융 동작과 관측 횟수를 정리한 뒤 실제 역할별 여정을 진행하는 것이다. 같은 P1/P2 선택은 다시 묻지 않는다. 지급 확인 누락 복구·환불 후 재구매와 G3 정산 조정은 별도 남은 범위다. 미확인 금액을 채우려고 할인 전체를 포인트·현금으로 추정하거나 플랫폼 보전을 추가하지 않는다.

2026-10-30 후속으로 사용자가 특정 거래 실행을 승인해 실제 TOSSPAY_V2 TEST 수동 환불·이용권·다운로드를 완료했다. 이번 거래의 취소 총액 9,000원·보고 포인트 24원과 고객/관리자 상세·NULL, 정지 선행·새 다운로드 거절·기존 URL 자연 만료를 관측했다. 이 문서의 내부 코드 검증 당시 범위·사례 수는 보존하며 실제 여정과 합산하지 않는다. 모든 할인/포인트 조합·실제 계정 잔액이나 혜택 재사용의 실측으로 확대하지 않는다.
