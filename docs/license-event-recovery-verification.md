# P3 A — 기존 이벤트 복구 검증 기록

2026-10-31 [결정 37](decisions.md#license-event-recovery)의 기존 복구 경로 검증·운영 절차 완성이다. 기준은 `70d2176` 이후 작업본이며 실행본은 파일·JAR 해시로 대조한다. 실제 거래에서 지급 누락 사고를 발견했다는 기록은 아니다.

**완료:** P3 A 구현·관련 최종 검사 **1,951건**과 실제 지급/DLT/환불 대기 복구를 통과했다. 전용 자원을 정리하고 기존 컨테이너 23개·볼륨 57개를 보존했다. G2 전체 완료와 구분한다.

## 수정한 범위

| 관측한 문제 | 변경 |
|---|---|
| DLT 발행 미래 결과의 실패가 recoverer 밖으로 전파되지 않음 | `DeadLetterPublisher`가 발행 실패를 예외로 전파한다. 성공 계측도 발행 확인 뒤에만 한다. |
| 재투입의 `flush()`를 비동기 send 성공으로 간주 | 모든 재발행 결과를 제한 시간 안에 확인한다. 실패·미확정·중단이면 오프셋을 커밋하지 않는다. 중단 플래그도 보존한다. |
| 한 poll이 max보다 많이 가져오면 미처리분까지 소비 위치를 커밋 | 실제 발행한 레코드의 파티션별 다음 오프셋만 명시적으로 커밋한다. 운영 소비자는 auto-commit=false·earliest를 명시한다. |
| 운영 API가 모듈 기본 보안 정책에 모두 막힘 | 명시적으로 활성화한 Order·License의 DLT 조회/재투입과 Outbox 건별 회수에 ADMIN을 허용한다. MEMBER·익명과 Outbox 일괄 회수는 차단한다. 기본 비활성과 Gateway 미노출을 유지한다. |
| 아직 없거나 DLT 형식이 아닌 토픽의 운영 요청이 500 | 요청 오류를 400으로 안내한다. 실제 브로커/발행 오류를 성공이나 빈 목록으로 바꾸지 않는다. |

새 업무 테이블·자동 재투입 작업자·별도 지급 엔진·새 지급 확인 이벤트 생성은 추가하지 않았다. [운영 절차](license-event-recovery-runbook.md)와 [읽기 전용 SQL](../tools/operations/license-recovery.sql)로 주문·보유권·지급 확인·원본 Outbox/Inbox를 연결하고, 운영자가 원인·실행·업무 결과를 기록하도록 했다.

## TDD와 계층별 검증

- 최초 OCI Red는 12건 중 3건 실패였다. DLT 발행 실패, 재투입 비동기 실패, max 밖의 미발행 레코드까지 커밋하는 사례를 재현했다.
- Order·License의 새 접근 검사에서 기존 정책은 ADMIN의 네 경로를 모두 403으로 막았다. 양쪽 8개 허용 사례를 Red로 확인한 뒤 필요한 경로만 열었다. MEMBER·익명 검사는 계속 차단을 요구한다.
- 첫 보완 실행에서 예외의 패키지 기대값을 Apache Kafka 예외에서 실제 Spring Kafka 예외로 바로잡았다. 생산 코드의 실패 전파를 취소하거나 실패를 성공으로 바꾼 보정이 아니다.
- 실제 Kafka의 `DltReplayIntegrationTest`는 두 파티션 6건을 max=2로 나눠 재투입하고 운영 객체 재생성 뒤에도 누락되지 않음을 확인했다. 일부 발행 성공 뒤 다른 발행이 실패하면 커서는 남으며 다음 실행으로 두 원본을 회수한다. 먼저 성공한 이벤트의 중복 1건도 실제로 관측한다.
- 두 모듈의 `*RecoveryOpsIntegrationTest`는 실제 MySQL에 생성한 OrderPaid·LicenseGranted를 발행 경계의 실패 픽스처로 DEAD에 도달시킨다. 실제 ADMIN HTTP 회수와 실제 릴레이/Kafka 재발행 뒤 원래 eventId·본문·키·생성 시각이 같다. 이 단계의 PG/다른 모듈 전체 여정과 구분한다.
- 첫 전체 게이트의 기존 License 검사는 비활성 운영 경로의 ADMIN 응답을 403으로 기대해 실패했다. 새 권한 규칙에서 컨트롤러 자체가 없으므로 404를 기대하도록 바꾸고, 기본 컨트롤러 미등록과 MEMBER 차단을 유지했다. 성공한 태스크를 재사용한 후속 게이트가 통과했다.
- 실제 여정에서 생성 전 DLT를 너무 일찍 조회해 500을 관측했다. 두 HTTP 오류 사례를 추가 Red로 재현해 400으로 고쳤고, 공통 check·Order/License 접근 회귀와 일곱 bootJar를 다시 통과했다. 변경 없는 업무 DB 전체 검사는 반복하지 않았다. 관측 도구도 명시적인 없는 토픽 400만 생성 대기로 구분하며 500을 숨기지 않는다.

최종 사례 수는 선택한 모듈/계층의 **최신 성공 XML 합집합**으로 집계하며 재실행을 더하지 않는다. 공통 Kafka/메시징·Order·License·Payment는 check(해당 통합·과거 이행 포함), 공통 의존성이 있는 나머지 완료 모듈은 단위·아키텍처를 확인했다. 최종 게이트 명령과 회차별 재사용은 OCI 원자료 로그에 보존한다. 계층별 수치는 `test-summary.json`을 따른다.

| 모듈 | 단위·아키텍처 | 통합 | 별도 이행 |
|---|---:|---:|---:|
| common Kafka | 232 | 3 | — |
| common Messaging | 74 | 12 | — |
| Order | 371 | 181 | 13 |
| License | 114 | 60(이행 포함) | — |
| Payment | 373 | 244 | 9 |
| Auth·Studio·Review·Catalog·Store·Settlement | 265 | 이번 영향 범위의 전체 통합은 재실행하지 않음 | — |

합계 1,951건, 실패·오류·건너뜀 0건이다. 첫 전체 게이트와 재개 후의 성공 집계는 1,948건이었다. HTTP 오류 후속의 3개 신규 사례와 관련 회귀를 반영한 최신 합집합이 1,951건이다. 첫 게이트는 `CI=true`, 재개 게이트는 통과한 태스크를 재사용해 `103 actionable tasks: 5 executed, 98 up-to-date`로 끝났다. 후속은 공통 check·두 모듈 단위와 일곱 bootJar가 통과했다. 세 명령 회차를 합산한 실행 수가 아니다.

DLT 발행 결과를 확인하고 실패를 전파하는 설정의 프레임워크 의미는 [Spring Kafka 공식 문서](https://docs.spring.io/spring-kafka/reference/3.3/kafka/annotation-error-handling.html#publishing-dead-letter-records)와 실제 사용 버전 3.3.10에 대조했다.

## 실제 여정의 경계와 실행 자료

OCI 전용 디렉터리는 `sng:/tmp/esd-p3-recovery-20261031.c7QADutN`, 로컬 도구·수집 경로는 Git 제외 `runs/p3-recovery-20261031/`다. Auth·Gateway·Catalog·Order·Payment·License·Download 일곱 앱과 전용 MySQL·Kafka·Redis·MinIO를 사용한다. PG는 가짜 자격의 루프백 HTTP 계약 픽스처이며 실제 외부 금융 동작은 0건이다.

첫 동시 기동에서는 회귀 검사와 일곱 JVM 기동이 겹쳐 License DB 연결 시간이 초과됐다. 구매 전 전용 앱 프로세스만 종료하고 같은 JAR를 두 활성 프로세서·순차 기동으로 실행하자 정상화됐다. 업무 자료 초기화·공유 설정 변경·제품의 DB 연결 정책 변경으로 우회하지 않았다. 이후 HTTP 오류 보완의 최신 JAR로 전용 앱을 갱신하고, 같은 계정/역할로 다시 인증해 기존 주문·결제·DLT를 이어간다.

기존 공유 자료의 기준은 보존된 이전 인벤토리의 컨테이너 23개·볼륨 57개다. 이번 시작 시의 현재 상태를 다시 저장했고, 동시에 돌던 이번 Gradle Testcontainers만 별도 목록으로 구분했다. 전후 원본 스냅샷과 범위는 `before-with-tests.json`·`before.json`·`baseline-scope.json`에 남긴다.

## 실제 업무 결과와 정리

대상은 새 전용 주문 `eda0a0e0-cdb4-4267-bc79-2c241cae3df6`, 결제 `853c7a6b-82da-4060-a81c-19e7b40d8bba`다. 실제 Auth의 고객·다른 고객·ADMIN으로 Gateway BFF/PKCE 인증을 했으며 업무 API는 쿠키로 호출했다. 운영 API 검증에는 같은 전용 Auth의 ADMIN 서버 보관 토큰을 내부에서 읽어 서비스에 직접 제시했다. 토큰·쿠키·서명 URL·설정 비밀은 결과 수집에서 제외했다.

| 단계 | 실제 관측 |
|---|---|
| 지급 전 중단 | 전용 License DB의 INSERT 오류를 발생시켰다. 최초·재시도 모두 롤백된 뒤 원래 OrderPaid가 DLT에 남았다. Order/Payment는 PAID, 보유권·지급 Inbox는 0건이다. 고객 환불은 PENDING_LICENSE_GRANT/공개 PROCESSING이며 PG 취소 POST는 0건이다. |
| 지급 재개 | 원인을 제거하고 ADMIN이 같은 OrderPaid 1건을 기존 DLT API로 재투입했다. 원래 구매 근거의 보유권과 Inbox가 각각 1건 생겼다. |
| 지급 확인 전 중단 | 별도로 설치한 Order 지급 확인 INSERT 오류로 원래 LicenseGranted가 DLT에 남았다. 실제 보유권은 ACTIVE이고 지급 확인·확인 Inbox는 0건이다. 환불은 지급 확인 대기를 유지하고 PG 취소 POST는 0건이다. 고객 다운로드는 200이며 ZIP 164바이트·원본 SHA-256이 일치한다. |
| 재시작 뒤 확인 재개 | Order를 같은 최신 JAR로 재시작해도 DLT가 남았다. 원인을 제거하고 ADMIN이 같은 LicenseGranted 1건을 재투입하자 확인·확인 Inbox가 각각 1건 저장됐다. 기다리던 환불의 정지 요청 하나가 연결돼 실제 License가 SUSPENDED가 됐고 PG 계약 픽스처에 취소 요청 1건이 도달했다. |
| 완료 범위 | 지급 확인 대기는 해소됐고 환불은 PENDING_PAYMENT_REFUND/공개 PROCESSING이다. 픽스처가 REQUESTED로 응답하므로 환불 COMPLETED·금융 성공으로 표시하지 않았다. 새 다운로드는 403이다. |
| 중복 | 저장된 원래 OrderPaid와 LicenseGranted를 각각 두 번 더 전달하고 실제 소비자 커서 전진을 확인했다. 보유권·지급 확인·각 Inbox·LicenseGranted Outbox·정지 요청은 각각 1건이다. 같은 licenseId·acquiredAt·구매 근거와 SUSPENDED를 유지하고 추가 PG 취소 POST도 없다. |

보유권 ID는 `2594e4fb-4959-4839-af22-88900f42aeee`, 원래 OrderPaid ID는 `008afc82-01b7-4f71-b7d7-5f51f774c048`, LicenseGranted ID는 `3062d625-a7cb-47dd-83ba-c34ad8b55898`이다. 읽기 전용 대조 SQL을 지급 전·확인 전·복구 후에 실제 실행했다. 원래 두 이벤트를 새 ID로 재생성하거나 업무 행·시각을 수정해 성공 상태를 만들지 않았다.

최신 실행 소스 **1,135개**의 SHA-256과 실제 일곱 실행 JAR의 해시·프로세스 경로를 대조했다. 관련 Outbox **8개는 모두 SENT**다. 두 DLT의 재투입용 커서 뒤에는 미처리 건이 없고 원래 Kafka 레코드는 보존 범위 안에 남는다. `before-recovery.json`·`confirmation-pending.json`·`after-confirmation.json`·원래 이벤트·`order-restart.json`·`ops-api-audit.json`·다운로드 결과·세 번의 진단 SQL 결과·`fault-journey-result.json`·`verification-integrity.json`이 근거다. JUnit XML과 실행 로그는 별도로 보존한다.

이번 앱 일곱 개와 PG 픽스처, 소유가 확인된 전용 컨테이너 네 개를 종료·제거했다. 전후 기존 컨테이너 **23개**의 ID·상태·시작 시각·재시작·health·mount와 볼륨 **57개**가 같다. mount 배열 순서만 정규화해 비교했고 원본 스냅샷도 보존했다. 전용 DB/Kafka/MinIO bind 자료와 결과는 보존했다. 공유 웹훅·실제 PG 설정과 거래는 변경하지 않았으며 실제 PG 금융 동작은 0건이다.

G2 전체 종료·P4 재구매·G3 정산은 이 기록으로 대신 완료하지 않는다. 원본 부족·모순 기록은 운영 확인 대상으로 남기고 별도 권한 재구성이나 자동 재투입으로 확장하지 않았다.
