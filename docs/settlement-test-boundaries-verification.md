# Settlement 테스트 경계 정비

2026-11-02. 사용자는 실제 PG TEST 전환 뒤 정산 검증을 위해 모의 PG 서비스를 다시 추가할 필요가 있는지 조사를 요청했다. 상시 서비스 복원 대신 도메인·HTTP·서비스 계약·현재 허용 입력의 DB·실제 공급자 연동을 구분하는 구조를 설명했고 `실행`을 지시했다. [결정 46](decisions.md#pg-test-boundaries-without-provider-service)을 따른다.

## 이번 변경의 범위

이번 작업은 **테스트·시험 자료만 변경**한다. 이전 [비용 연결 167건 검증](settlement-pg-cost-posting-verification.md)의 운영 소스·설정·마이그레이션과 공개 API를 유지한다. 이전 작업본 `/tmp/esd-pg-cost-posting-20261102.pLSbPcJ4/work`와 원자료를 보존한다. `mock-pg`를 상시 서비스로 복원하거나 새 서비스·DB·수수료 엔진·운영 모의 모드를 추가하지 않는다.

현재 PortOne 입력의 `PgEvidence`·`PgFunding`·V9 CHECK가 TEST 계약인 사실을 테스트 편의로 바꾸지 않는다. 이전 `futureLiveSale`의 운영 CHECK 제거, 저장된 TEST 근거의 LIVE 변경, 매출 행 재삽입 및 가상 환불 SQL 삽입을 제거했다. 기존 SIMULATED 비용 경로도 유지한다. 정상 LIVE 근거 수용 계약은 이번에 구현하지 않았다.

## 실제 사례와 선택 이유

- [Stripe 자동화 테스트](https://docs.stripe.com/automated-testing)는 반복 CI에서 모의 응답을 사용하고 실제 API 계약은 테스트 환경에서 별도로 확인하는 방식을 안내한다.
- [stripe-mock 공식 설명](https://github.com/stripe/stripe-mock)은 Stripe SDK 테스트에 사용하는 도구임을 밝히며, 상태 저장이나 금융 업무 재현을 범위에서 제외한다.
- [Adyen 보고서 준비 가이드](https://docs.adyen.com/payouts/payout-service/prepare-reports/)는 운영 대사 전에 완전한 샘플 보고서 또는 테스트 환경 보고서로 절차를 검사하도록 권장한다.
- [Shopify Payments 테스트](https://help.shopify.com/en/manual/payments/shopify-payments/testing-shopify-payments)는 시험 거래가 지급 내역/보고서에 나타나지 않는다고 명시한다.

이 사례를 현재 시스템에 적용한 설계 판단은 외부 결제사의 업무를 복제하기보다 필요한 응답과 검증 범위를 관리하는 것이다. 현재 [토스페이 TEST 정산 API](https://docs-pay.toss.im/reference/settlement/detail)가 빈 목록/0원 합계를 반환한다는 제약은 유지한다. 새 공급자 샘플이나 실명세를 확보하기 전에는 직접 만든 자료를 공급자 발행 자료로 표시하지 않는다.

## 변경한 구조

| 영역 | 파일과 책임 | 실제로 사용하는 자원 |
|---|---|---|
| 도메인 | 기존 `PgStatementDomainTest`, `PgCostPostingTest` 등. 부호/금액·월 귀속·환불 반환·미확정 규칙 | Java 객체와 명시적 읽기 투영 대역 |
| HTTP 어댑터 | 기존 `TossPayStatementHttpTest`. 요청 경로·본문·커서·원문·합계·엄격한 JSON·오류/누락 | 공통 루프백 HTTP 응답 서버와 실제 HTTP 어댑터 |
| 서비스 결정 | 새 `ReconcilePgStatementContractTest`. 비용 연결·중복·환불 전체 합계·늦은 투영·충돌·검증 근거 거절 | 실제 서비스/도메인 코드와 저장소 대역 |
| 조회 조합 | 새 `ReadPgFinancialsContractTest`, `ReadPgPayoutsContractTest`. 마감본 보존·부분합·NULL/0원·반환·업무 월/지급일·추적 정보 | 실제 조회 서비스/DTO와 명시적 저장소 집계/조회 투영 |
| 현재 계약의 DB·API | `PgStatementIntegrationTest`. 수집/원본·중복·동시 수집·현재 TEST 근거·제약·실제 쓰기 롤백·재처리·미연결 지급일 조회·권한 | 실제 앱·HTTP·MySQL와 생성자 검증을 통과한 TEST 구매 |
| 이행/이벤트 | 기존 `SettlementMigrationIntegrationTest`, `SettlementIntegrationTest`, `PortOneSettlementIntegrationTest` | 기존 MySQL/Kafka 계약과 이행 검사 유지 |
| 실제 공급자 | 기존 역할별 PortOne TEST 여정 기록과 실제 공급자 계약 확인 | 이번 작업에서는 새 실행하지 않음 |

기존 `src/test`·`src/integrationTest` 소스셋과 `check` 포함 정책을 유지한다. 루트 Gradle·애플리케이션 빌드 파일·프로덕션 의존성은 변경하지 않는다. 재사용 지원 클래스는 기존 통합 classpath가 참조하는 test 출력에 두며, 별도 캐시 최적화나 속도 개선을 입증한 것으로 설명하지 않는다.

### 공통 HTTP 서버와 시험 자료

`support/TossPayStatementServer`가 요청 기록·정해진 커서별 응답·후속 HTTP 실패를 제공한다. 단위 테스트는 각 테스트의 시작/종료에서 관리하고, 통합 테스트는 같은 Spring 컨텍스트의 서버를 재사용하며 테스트 JVM 종료 때 정리한다. 각 사례 전 응답·요청·오류 상태를 초기화한다. 서버에는 승인 처리·수수료 계산·원장·DB·배포 설정이 없다.

`src/test/resources/pg/tosspay/approval-page.json`과 `refund-page.json`은 한 명세의 두 페이지다. 승인 9,000원·수수료 -300/VAT -30, 환불 -9,000원·반환 200/VAT 20, 전체 합계 -110원을 직접 명시한다. 예상값을 운영 계산기로 만들어 검증하지 않는다. README에 **직접 만든 SYNTHETIC 자료**와 공식 형식 출처·검토일을 적고, 실제 거래/사업 요율/실지급 자료와 구분했다.

### LIVE 합성 자료는 저장소 대역에서만 사용

`PgCostContractFixtures`는 미래 LIVE 읽기 투영을 가정하는 명시적 대역이다. `SettlementSale`, `PgTransactionRecord`, `PgFunding`, `SettlementRefund`의 읽기 값을 모의로 제공하며 실제 승인을 만들거나 DB에 저장하지 않는다. 명세·항목·비용 연결과 서비스/도메인 처리는 실제 코드를 사용한다. Reflection은 저장소가 생성할 `id`만 부여하며 환경·금액·업무 값을 변경하는 데 사용하지 않는다.

저장소 대역으로 실행하는 서비스에는 Spring 트랜잭션 프록시·DB·잠금이 없다. 이 결과를 SQL 원자성·실제 동시 INSERT·정상 LIVE 입력의 증거로 사용하지 않는다.

## 보장 범위의 이동과 남은 공백

| 선행 합성 DB 검증의 사례 | 이번 기본 검증의 위치 | 해석 |
|---|---|---|
| 늦은 비용의 업무 월/지급일 연결·최초 검증 보존 | 서비스 계약·조회 조합·기존 도메인 | 값/결정 검증. LIVE 비용의 SQL 집계·HTTP 양성 전체 경로는 별도 |
| 여러 환불 행·반환 0원/미도착 | 서비스 계약·조회 조합·도메인 | 전체 관측 합계와 NULL/0원의 의미 검증 |
| 모호한 승인·초과 환불 뒤 확정 보류 | 서비스 계약·지급일 조회 계약 | 서비스 결정/표시 검증. SQL의 활성 비용 필터는 별도 |
| 늦은 원매출 도착·재처리 | 현재 TEST 입력의 실제 DB 재대사 + 미래 투영의 서비스 계약 | 실제 DB에서는 SOURCE_ENVIRONMENT_PENDING·비용 미확정 유지 |
| 비용 INSERT 실패 후 전체 롤백 | 현재 TEST 대사 항목 UPDATE 실패/롤백을 실제 DB에서 검증 | 같은 검증이 아니다. LIVE 비용 INSERT의 원자성은 공백 |
| 서로 다른 명세의 동시 첫 비용 연결 | 현재 DB의 동시 원본 수집 + 서비스 계약의 반복 비용 연결 | 같은 검증이 아니다. 비용 INSERT/잠금 경합은 공백 |

**정상 LIVE 입력 → 비용 연결 성공 → 실제 비용 SQL 집계/조회, 동시 첫 비용 INSERT, 해당 INSERT 실패 롤백은 현재 계약과 제약을 보존한 상태에서 아직 검증하지 못했다.** 이를 다시 닫으려면 정식 LIVE 승인 근거 수용의 도메인·스키마·조회 계약이 먼저 필요하다. 이번 정비에서 단위 테스트를 추가했다고 이 공백을 완료로 표시하지 않는다. 이전 167건의 제약을 일시 해제한 합성 DB 검증은 해당 소스·원자료와 함께 당시 근거로 보존한다.

## 운영 제약과 실제 롤백 검사

새 제약 검사는 생성자를 통과한 `PORTONE_TEST` 구매를 정상 서비스로 저장한다. 채널만 LIVE로 바꾸려는 SQL이 원래 `ck_pg_transaction_funding`에서 MySQL 3819로 거절되고 원본 행이 동일함을 확인한다. `PgEvidence(PORTONE_LIVE,...)`의 거절도 확인한다. 성공 자료를 만들기 위한 운영 제약 해제는 없다.

별도 롤백 검사는 원본을 먼저 저장한 뒤 정상 TEST 구매를 기록한다. 대사 결과가 MISSING_SALE에서 SOURCE_ENVIRONMENT_PENDING으로 바뀌는 실제 UPDATE를, 테스트가 추가한 `ck_fixture_reconcile_failure`로 실패시킨다. 원래 항목·수집 원본 보존을 확인하고 **테스트가 추가한 제약만** 제거한 뒤 저장 원본을 재처리한다. 기존 원본 수집의 실패 주입도 같은 방식으로 테스트 전용 CHECK만 사용한다. 이 경우에도 실제 비용은 미확정이며 비용 연결 행은 0개다.

## OCI 검증

모든 빌드·테스트는 `ssh sng`의 `/tmp/esd-settlement-test-boundaries-20261102.FJn6GTfZ/work`에서 수행한다. 기존 167건 작업본을 별도 디렉터리로 복사했고 운영 소스/리소스 **902개**를 변경하지 않았다. 변경 전 소스 1,316개와 이번 소스 해시를 대조한다.

최초 단위 실행 120건 중 3건은 환불 대역을 다른 Mockito 설정 도중 생성한 픽스처의 `UnfinishedStubbingException`이었다. 대역을 먼저 생성하도록 고친 뒤 단위·아키텍처 120건 및 통합 컴파일이 통과했다. 이어 현재 허용 입력만 사용하는 PG 명세 집중 DB/HTTP 검증 10건을 통과했다.

동작 변경을 위한 TDD와 테스트 재배치를 구분한다. 이번에는 운영 동작이 바뀌지 않으므로 인위적인 운영 코드 실패를 만들지 않았고, 기존 계약과 새 실제 제약/실패 주입 사례로 재배치를 검증했다.

최종 게이트는 필터·명세 갱신 옵션 없이 `CI=true ./gradlew :apps:settlement:check :apps:settlement:bootJar --console=plain`이다. 최종 **단위·아키텍처 120건 + 통합·이행 56건 = 176건**과 check·bootJar를 통과했다. 실패·오류·건너뜀은 0건이다. 아키텍처 47건, 이행 2건을 포함한다. 최종 Gradle 명령은 99.230초이며 전체 작업 시간이나 통제된 성능 비교가 아니다. 선행 167건과 이번 176건은 보장 범위가 다르므로 숫자 증가를 검증 강도 증가로 설명하지 않는다.

최종 소스·빌드 입력·시험 자료 **1,324개** 해시가 로컬·OCI에서 일치했다. 이전 검증본 **1,316개**도 원래 해시를 보존했다. 변경은 테스트/자료 **11개 파일**에 한정되며 운영 소스·설정·리소스/스키마 **902개**는 동일하다. API 명세도 그대로다.

새 bootJar SHA-256은 `2b2ad6682873427824fb10b00053e68a440c5c0e6850e5894aa93ac4fa7c323d`로 선행 JAR의 `58c654f4547fc31875c51ae51e696c6abb726d8c12e4093acb79a46883aeffb6`와 다르다. ZIP 내부 시간 정보 차이를 확인하고 중첩 의존 JAR까지 풀어 **47,964개 파일의 경로·내용 해시가 모두 동일**함을 대조했다. 중복 ZIP 엔트리는 없었다. JAR 바이트가 같다고 주장하지 않으며 `jar-comparison.json`에 비교 근거를 보존한다.

첫 자원 원본 비교는 기존 Kafka 컨테이너 하나의 mount 배열 순서가 달라 false였다. 컨테이너 ID·시작 시각·재시작·실행/건강 상태·마운트 구성과 볼륨 값은 같았으며, mount 배열 순서만 정규화한 비교는 true다. 원본 before/after와 최초 비교 결과는 보존하고 `resource-comparison.json`에 정규화 근거를 기록했다. 공유 컨테이너 **23개**·볼륨 **57개**를 보존했고 새 잔여 자원은 없다. 공유 자원 재시작·수정은 없었다.

원자료는 `runs/settlement-test-boundaries-20261102/collected/`의 최종 XML·명령/로그·원본/파생 자원 비교·JAR 비교·source manifest·`verification-summary.json`이다. 최종 게이트 뒤에는 문서만 편집했다.

원자료는 Git 제외 `runs/settlement-test-boundaries-20261102/`에 보존한다. 실제 PG 조회/승인/취소·LIVE 전환·은행 실수령 확인·송금·배포는 수행하지 않는다. G3 전체 완료와 구분한다.
