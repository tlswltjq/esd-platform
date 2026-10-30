# G2 미확정·실패·실제 복구·명시적 재시도 여정 검증

2026-10-30. 사용자의 `커밋 후 다음` 요청으로 누적 구현·실제 수동 환불 기록을 **`e11cfaa`**에 커밋한 뒤 R3의 남은 계약 검증을 진행했다. 같은 소스의 일곱 업무 앱과 실제 Auth·Gateway 세션, MySQL·Kafka·MinIO·Redis를 연결했다. **PG 경계는 루프백 PortOne HTTP 계약 픽스처이며 실제 PG 금융 동작은 0건이다.**

미확정 보존 → 확정 실패 → 실제 License 복구 → 고객의 명시적 재시도 → 같은 키·본문의 내구 재개 → 성공 응답 유실 뒤 조회 완료를 검증했다. 기존 [실제 TEST 수동 환불](g2-refund-role-journey-verification.md)과 증거 수준을 구분한다. 새 업무 정책·운영 코드·스키마 변경은 없다.

## 구성과 구매 근거

- 새 DB·Kafka·파일을 준비하고 픽스처 전용 설정만 사용했다. Payment 실행 프로세스의 API base URL이 `http://127.0.0.1:<fixture-port>`이고 실제 제공사 자격 증명이 아닌 픽스처 값임을 대조했다. 실제 PG·기존 성공/취소 거래와 공유 웹훅 설정은 사용하지 않았다.
- 실제 MEMBER 두 명·bootstrap ADMIN이 Gateway의 Authorization Code + PKCE S256·BFF 쿠키로 로그인했다. Catalog 출시 스냅샷과 PG 인증 결과는 경계 픽스처다. 주문 생성·승인 접수·PAID 반영·License 지급은 실제 업무 API/서비스·Outbox/Inbox를 거쳤다. 구매·결제·보유권 상태를 SQL로 넣거나 바꾸지 않았다.
- 새 구매의 Order·Payment·승인 기록은 각각 1개다. 금액은 9,000원·승인 실결제 8,976원·할인 24원이다. 실제 서버 시각으로 결제 후 4시간 이내 접수해 자동 환불 경로에 진입했다. 업무 시각과 원래 접수 자격을 바꾸지 않았다.
- 구매 직후와 실제 복구 후 새 ticket/MinIO GET 200·원본 ZIP 162바이트를 확인했다. SHA-256은 `3b1d8646e64abe4e06358514ea219d542d9da14f0b114eae4f80993e0cfe93f6`이다. 서명 URL은 수집하지 않았다.

대상 주문은 `5dd53e1b-f3a7-439b-9fa7-80ac5c6a83fe`, 결제는 `aa8f9993-99f9-4293-b690-31b40f61c653`, 상품은 `g2-fault-e9imlos9`다. 이 식별자는 실제 PortOne 거래가 아닌 격리된 계약 검증의 업무 기록이다.

## 단계별 실제 관측

| 단계 | 확인한 업무·고객 결과 |
|---|---|
| REQUESTED 미확정 | 제공사가 REQUESTED 영수증을 반환하고 조회에도 미확정 이력을 유지했다. 실제 License SUSPENDED, 고객 PROCESSING·retryableAttemptId=NULL·확인 반환 금액 NULL을 유지했다. 새 ticket 403, 임의 재시도 409, 타인 재시도 404·CSRF 누락 403을 확인했다. 같은 최초 환불 접수는 기존 환불을 반환했다. |
| 금융 실패·복구 전 | License의 Kafka 소비를 끈 실행본으로 잠시 재시작해 Library API는 유지했다. 픽스처의 동일 영수증을 FAILED로 전환하고 원구매 PAID·미취소·실패 이력을 실제 Payment 조회 작업자가 대조했다. Order는 PENDING_LICENSE_RESTORATION, License는 SUSPENDED·실제 복구 기록 0개였다. 고객 재시도 409·새 ticket 403을 확인했다. |
| 실제 복구 완료 | 같은 License JAR의 Kafka 소비를 다시 켜자 대기 중인 복구 이벤트를 실제로 처리했다. License ACTIVE·복구 기록 1개, Order 환불 FAILED·해당 실패 시도의 retryableAttemptId가 나타났다. 새 다운로드는 200·원본 일치다. 최초 환불 접수를 반복해도 자동 재시도나 새 실행이 생기지 않았다. |
| 명시적 후속 시도 | 고객이 원래 실패 attemptId를 지정해 POST했다. HTTP 202·같은 명령 반복의 동일 영수증, 새 시도 하나와 이전 시도 연결을 확인했다. 실제 두 번째 정지 뒤 License SUSPENDED·ticket 403이다. 원래 환불 ID·접수 시각과 실패 이력을 보존했다. |
| 미확정 POST 재개 | 후속 시도의 취소 POST에 503을 반환했다. 이전 실패 이력이 조회돼도 새 시도를 실패로 간주해 복구하지 않았다. 고객 PROCESSING·추가 재시도 409·SUSPENDED를 유지했다. 반복 POST의 같은 키·UTF-8 본문을 대조했다. |
| 프로세스 재시작 | 미확정 중 실제 Payment를 같은 JAR로 재시작했다. DB의 최초 송신 준비 시각·본문과 후속 HTTP의 같은 키·본문이 유지됐다. 새 시도·새 키나 기한 재설정을 만들지 않았다. |
| 성공 응답 유실 | 후속 POST를 픽스처가 성공 처리한 뒤 HTTP 응답을 보내지 않고 연결을 끊었다. 실제 조회 작업자가 CANCELLED·원승인·성공 취소 합계/포인트를 확인해 고객/관리자 COMPLETED로 수렴했다. 출처는 PG_QUERY_UNATTRIBUTED다. |
| 완료 후 | License SUSPENDED·새 ticket 403, 추가 재시도 409다. 다음 POST·조회 일정은 모두 NULL로 종료됐다. 관련 Outbox 16개는 SENT이며 신규 주문·결제·승인은 각각 최초 1개를 유지했다. |

License 소비 중단/재개는 전용 실행 프로세스의 `SPRING_KAFKA_LISTENER_AUTO_STARTUP` 설정으로 수행했다. 업무 자료·시각을 수정해 복구 전 상태를 만든 것이 아니다. 복구 전에는 실제 보유권이 SUSPENDED였고, 복구 이벤트를 실제로 처리한 후 ACTIVE가 됐음을 DB·고객 API·파일로 각각 확인했다.

## 시도와 금액의 구분

환불 `d2542ee7-a740-473a-b3bd-806fd7623fff`의 첫 시도는 `493402dd-c6b9-4f93-abc7-a369e6a225c0`으로 FAILED다. 명시적 후속 시도 `1a567b37-b685-4381-85d9-88e451fd7c3f`는 이전 시도와 연결된다. 픽스처의 취소 POST는 총 4회이며 후속 시도 3회의 키·본문이 같다. 픽스처의 성공 취소는 1건이다. HTTP 재송신 수·실패/후속 시도 수·성공 취소 수를 구분한다.

최종 금액 상세는 주문 9,000·승인 실결제 8,976·할인 24·취소 총액 9,000·보고 포인트 **12원**이다. 픽스처가 보고한 12원을 보존했고 할인액 24원으로 대신하지 않았다. 직접 근거가 없는 결제수단 반환액은 NULL이다. 고객·관리자 상세도 같다. 이 숫자를 실제 제공사·계정 잔액의 실측값으로 해석하지 않는다.

성공 응답을 받지 못했으므로 후속 Payment 실행 자체는 PENDING 상태에 금융 확인 근거 PG_QUERY_UNATTRIBUTED를 보존한다. 원구매 전액 취소의 조회 근거로 고객 환불은 COMPLETED지만, 우리 실행의 성공 귀속을 직접 영수증 없이 SUCCEEDED로 바꾸지 않는다. 금융 결과와 실행 귀속을 분리하는 기존 계약이며, 이 상태에서 새 환불 실행을 허용하지 않았다.

## 실행본·검증 도구·원자료

- 기준 소스는 `e11cfaabde99c1cd52edbe3bce81e224cf0f9f41`이다. 검증된 소스 1,149개와 같은 일곱 JAR를 재사용했고 소스·실제 실행 JAR의 해시를 대조했다. 선행 P2의 1,526건을 재실행하거나 이번 여정 관측과 합산하지 않았다.
- 검증 클라이언트의 재사용 BFF 세션이 401을 반환해, 같은 계정으로 다시 로그인하고 sub·역할이 같음을 확인하도록 도구를 보완했다. 재가입·업무 데이터 초기화·운영 코드 수정 없이 여정을 이어갔다. `observation-tool-corrections.json`과 세션 갱신 기록을 보존했다.
- 로컬 도구·수집본은 Git 제외 `runs/g2-refund-fault-e9imlos9/collected/`, OCI 자료는 `sng:/tmp/esd-g2-refund-fault-20261030.Gpp4P3Jz`다. `runs/g2-refund-fault-current`는 해당 로컬 경로를 적은 파일이다.
- `requested-result.json`·`pending-restoration-result.json`·`restored-result.json`·`retry-receipt.json`·`unknown-retry-result.json`·프로세스 재시작 기록·완료 응답/시도·`fixture-state.json`·실제 권한 이력·Outbox·`fault-journey-result.json`이 단계별 근거다. `verification-integrity.json`·`cleanup-result.json`은 실행본·자원 대조다.
- 암호·쿠키·픽스처 인증 토큰·서명 URL은 수집본과 Git에서 제외했다. 검증용 HTTP 픽스처와 도구는 실행별 runs 경로에 있고 운영 PG 모듈을 추가하지 않았다.

## 정리와 다음 범위

이번 일곱 앱과 HTTP 픽스처, 전용 컨테이너 네 개를 종료·제거했고 DB·Kafka·MinIO의 bind 자료와 검증 결과를 보존했다. 기존 컨테이너 **23개**의 ID·시작 시각·재시작·health·mount와 볼륨 **57개**가 같다. mount 배열 순서만 정규화해 대조했으며 원본 스냅샷도 보존했다. 공유 웹훅·실제 PG 설정과 거래는 변경하지 않았다.

R3의 실패·실제 복구·명시적 재시도·응답 유실 경계는 위 계약 픽스처 수준으로 확인했다. 실제 PortOne 장애 재현이나 새 실제 TEST 자동 환불의 관측으로 표시하지 않는다. 추가 실제 TEST 구매·취소의 범위는 별도로 정하며, 이미 완료된 실제 거래와 이번 픽스처 스크립트를 반복하지 않는다.

다음 일반화 논의는 [실행 계획](g2-refund-journey-plan.md)의 P3, **PAID 이후 지급 또는 지급 확인 누락의 근거 기반 복구**를 권장한다. 배경·현재 계약·대안·비용을 설명하고 한 쟁점의 사용자 선택을 받은 뒤 구현한다. 환불 후 재구매 P4·G3 정산 조정과 G2 전체 종료는 별도 남은 범위다.
