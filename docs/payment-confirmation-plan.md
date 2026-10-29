# Payment 서버 승인 연결 작업

2026-10-29 서버 승인 연결 구현·로컬 검증 완료. [결정 32](decisions.md#order-expiry-approval-admission)와 [Order 기반 검증](order-payment-lifecycle-plan.md)을 따른다.

## 합의와 구현 계약

- 사용자는 승인 응답 유실 복구의 A안을 선택했다. 최초 송신 준비부터 3시간 안에 같은 승인 ID·멱등 키·본문으로 자동 재송신하고 결과 조회를 병행한다. 원래 준비 시각·키·본문을 초기화하지 않는다. 3시간 경과 뒤에는 자동 POST를 중단하고 GET을 이어가며, 시간만으로 실패·새 결제·새 키를 만들지 않는다.
- MEMBER의 본인 결제에 requestId·공급자 txId·paymentToken을 받는 승인 접수 API와 비밀값 없는 진행 조회를 연결한다. 접수와 실제 결제 성공을 구분한다.
- Payment.core.port의 Order 승인 접수 계약을 HTTP adapter/config로 구현한다. Order 영수증 없이 PG 승인·구매 성공을 만들지 않는다.
- 공급자 거래 조회로 원구매·금액·통화·TEST 채널·txId를 대조한다. confirm 본문에 txId·currency·totalAmount·isTest를 고정하고, 완료 뒤에도 서버 조회로 구매와 실제 성공을 확인한다.
- 토큰을 포함한 고정 본문은 AES-GCM으로 암호화해 보관하고 원본은 로그·응답·Order·Kafka에 넣지 않는다. 재송신은 복호화한 원래 바이트와 같은 키만 사용한다.
- 승인 접수·내구 작업·lease·최초 준비 시각·3시간 창·조회/재송신 일정을 저장한다. 외부 HTTP 동안 Payment 트랜잭션을 유지하지 않는다.
- Order 접수 응답이 유실돼도 같은 승인 ID로 재요청해 최초 영수증에 수렴한다. 만료가 확인되면 PG를 승인하지 않는다.
- 검증된 개별 실패는 Payment/Order의 최종 FAILED로 만들지 않는다. 해당 Order 승인 진행만 종료해 기한 내 PENDING 재진입·기한 후 EXPIRED로 조율한다. 과거 종료 재전송은 이후 진행을 바꾸지 않는다.
- HTTP 오류·토큰 오류·응답 유실만으로 금융 실패나 새 결제를 확정하지 않는다. 설명되지 않는 상태는 추가 POST를 멈추고 조사 대상으로 보존한다.
- 기존 ResolvePayment의 결과/Outbox 경로를 재사용하되 PortOne은 승인 접수 근거를 대조한 결과만 확정한다. 현재 모의 PG 계약은 유지한다.
- Gateway MEMBER/CSRF·본문/Bearer·Location·위조 회원 헤더 제거를 검증한다. 실제 PortOne 채널 전환·거래는 루프백 계약 검증과 구분한다.

## 공식 원자료와 검증 계획

[PortOne 수동 승인](https://developers.portone.io/opi/ko/extra/manual-confirm/readme-v2), [멱등·타임아웃](https://developers.portone.io/api/rest-v2), 공개 OpenAPI를 대조했다. 원본은 `runs/payment-confirmation-20261029/portone-openapi.json`에 보관했다. 실제 결제 API는 호출하지 않았다.

도메인 Red→Green, Order/PG HTTP 계약, DB 동시성·flush 롤백·일정/lease 재개·미확정/3시간 경계, 본인/타인/역할, Gateway·CSRF, 기존 Payment·Order 회귀와 명세를 검증한다. 로컬 빌드와 OrbStack의 빈 격리 DB·새 픽스처를 사용한다. 실제 금융 동작은 별도로 합의한 TEST 사례에서만 수행한다.


## API와 실행 조건

PortOne 모드에서만 다음 MEMBER API를 제공한다. API 접수는 202이며 DB 작업자가 별도로 진행한다. 요청 처리 중 PG나 Order를 호출하지 않는다.

```http
POST /api/v1/payments/{orderNo}/approvals
Authorization: Bearer <member-access-token>
Content-Type: application/json

{"requestId":"<한 인증 결과에 고정한 UUID>","txId":"<SDK의 txId>","paymentToken":"<SDK의 paymentToken>"}
```

응답은 requestId·orderNo·paymentId·receivedAt이며 Location은 같은 경로의 `/{requestId}`다. HTTP 접수 응답이 유실되면 같은 requestId·txId·토큰을 보내 최초 영수증을 받는다. 새 UUID로 바꾸지 않는다. GET Location은 `REQUESTED`, `ADMITTED`, `REVIEW_REQUIRED`, `FAILURE_CONFIRMED`, `SUCCEEDED`, `FAILED`, `EXPIRED`와 접수·고정 주문 만료·종결 시각을 반환한다. 응답은 no-store이며 토큰·거래 원문·암호문은 없다. 다른 구매자는 404다.

개별 승인 FAILED는 같은 주문 재결제를 허용하는 근거이며 Payment/Order의 전역 FAILED와 다르다. EXPIRED는 Order가 진행 없이 만료했거나 검증된 실패 뒤 기한이 지난 사실을 알려준 경우다. REVIEW_REQUIRED와 FAILURE_CONFIRMED는 새 인증/결제를 열 수 있는 상태가 아니다. 실제 결제 완료 여부는 기존 내 결제 조회·주문 조회로 확인한다.

- Order와 Payment에 동일한 `PAYMENT_ORDER_SERVICE_KEY`를 설정한다. 내부 승인/실패 API는 이 키만 허용하며 MEMBER Bearer로 호출할 수 없다. Gateway는 이 내부 경로를 제공하지 않는다.
- Payment의 `PAYMENT_APPROVAL_PAYLOAD_KEY`는 새로 생성한 32바이트 키를 Base64로 제공한다. 재시작해도 동일 키를 유지하고, 미완료 암호문을 복구할 수 없게 키를 교체하지 않는다. 저장소·콘솔·응답에 실키를 남기지 않는다. `ORDER_URI`는 Payment에서 접근할 Order 주소다.
- Order 자동 만료는 `ORDER_EXPIRY_ENABLED` 기본 true, 1초 주기·최대 100건으로 DB의 PENDING을 검색하고 주문별 잠금 뒤 재검증한다. PAYMENT_PROCESSING·PAID를 스캔으로 종료하지 않는다. 테스트는 자동 스케줄을 끄고 같은 업무를 명시적으로 실행한다.
- Payment의 `PAYMENT_PROCESSING_ENABLED`가 true일 때 DB 승인 작업자도 자동 실행한다. 미확정 건은 원래 일정·lease를 읽어 재개한다.
- 실제 사용 전 [PortOne 채널 설정의 수동 승인](https://developers.portone.io/opi/ko/extra/manual-confirm/readme-v2)을 켜야 한다. SDK가 반환한 paymentToken·txId를 접수 API에 보내고 Location으로 조회한다. 기존 자동 승인 채널을 켜 둔 상태를 이 서버 코드만으로 차단할 수는 없다. 현재 실제 채널 설정·기존 런타임은 변경하지 않았다.
- 기존 `tools/verification/support/portone_checkout.html`은 Order와 무관한 직접 PG 탐침이다. 일반 구매 여정의 연결 화면으로 간주하지 않는다. 전용 고객 화면 추가는 현재 백엔드 범위 밖이다.

## 현재 검증 경계

외부 PG·Order HTTP는 루프백 공급자 픽스처, 실제 인프라는 OrbStack의 격리 MySQL·Kafka·Redis다. 실제 Order 상태 전이·영수증·동시성과 HTTP 보안은 Order 모듈 통합 테스트에서 따로 검증한다. 새 서버 승인 API를 두 실제 서비스와 실제 TEST 채널에 연결한 전체 사용자 여정으로 확대해 주장하지 않는다.

기존 자동 승인 전용 PortOnePgHttpGateway는 제거하고, Order 접수 영수증과 인증 시도 ID를 대조하는 AdmittedPortOnePgGateway로 교체했다. 기존 금액·구매·채널·비용 미확정 검증은 새 HTTP 어댑터의 테스트로 옮겼다. SIMULATED 공급자 계약은 유지한다.

## 상태를 읽는 기준

| 관측 | Order | 승인 작업 | Payment | 고객의 다음 행동 |
|---|---|---|---|---|
| 인증 결과를 202로 접수 | 아직 PENDING일 수 있음 | REQUESTED | PENDING | Location 조회. 결제 성공으로 표시하지 않음 |
| 기한 내 Order 접수 영수증 저장 | PAYMENT_PROCESSING | ADMITTED | PENDING | 기존 진행의 결과 확인. 다른 결제창을 새로 열지 않음 |
| 기한 내 개별 실패·Order 종료 확인 | PENDING | FAILED | PENDING | 같은 주문·금액으로 새 인증 시도 가능 |
| Order가 만료를 응답했거나 기한 후 개별 실패 종료를 확인 | EXPIRED | EXPIRED 또는 FAILED | EXPIRED | 현재 상품 조건으로 새 주문 생성 |
| PG 성공 재조회·Payment/Outbox 커밋 | 이벤트 반영 뒤 PAID | SUCCEEDED | PAID | 기존 주문·라이브러리의 후속 반영 확인 |
| HTTP 결과 유실·3시간 경과·운영 확인 필요 | 진행 상태 유지 | ADMITTED 또는 REVIEW_REQUIRED | PENDING | 기존 결과 확인을 이어감. 시간만으로 새 결제를 만들지 않음 |

202·ADMITTED·송신 준비는 각각 내구 접수·Order 허용·송신 준비 사실이다. SUCCEEDED는 Payment의 금융 결과/Outbox 저장이며 OrderPaid·License 지급까지 동기 완료됐다는 뜻은 아니다. 실패 통지의 응답이 유실된 FAILURE_CONFIRMED도 최초 종료 영수증 확인 전까지 새 승인 접수를 차단한다. Order 스캔 자체는 Payment에 만료 이벤트를 보내지 않으므로, 접수 요청이 없는 이탈 건의 Payment는 PENDING으로 남을 수 있다. 주문 만료 판단은 Order가 소유하며 checkout은 고정 기한 후 열리지 않는다. 이 사본 정리·불필요 조회 종료는 남은 G1 운영 연결 범위다.

공개 명세 대조에서 기존 Order 12개 경로·14개 연산과 기본 Payment 9개 경로·10개 연산을 모두 유지했다. 기본 PaymentResponse의 EXPIRED 추가 외 기존 연산 삭제는 없다. PortOne 조건부 명세는 checkout·webhook·승인 접수/조회가 포함된 별도 `payment-portone.json`으로 13개 경로·14개 연산을 검증한다. Order 내부 접수·실패 API는 공개 명세에 노출하지 않는다.

## 구현·검증 완료 기록

2026-10-29 로컬 구현과 OrbStack 격리 검증을 완료했다. 이 절의 완료는 서버 승인 연결 범위이며 G1 전체·배포·실제 PG 거래 완료와 구분한다.

- Payment의 V29는 암호화된 고정 본문·토큰 지문·원구매·승인 상태·최초 준비·다음 일정·lease와 명령/공급자 시도/진행 중 작업의 유일 키를 저장한다. V30은 기존 금융 성공/실패 제약을 유지하면서 금융 결과를 만들지 않는 EXPIRED를 허용한다.
- Order V25는 검증된 개별 실패의 최초 종료 영수증을 보존한다. 같은 주문 잠금으로 기한 내 PENDING·기한 후 EXPIRED를 결정하고, 과거 종료 재전송은 이후 진행을 풀지 않는다. V26은 PENDING 만료 스캔 인덱스다.
- 도메인 최초 Red 7건 중 6건 실패 뒤 구현했다. 별도의 아키텍처 위반은 값 레코드의 domain 배치·port 인터페이스 경계·config의 inbound 의존 제거·기존 유스케이스 네이밍으로 바로잡았다. 이를 금융 업무 Red와 합산하지 않는다.
- 최초 관련 MySQL 검증에서 두 만료 사례가 기존 `chk_purchase_payment_state`의 EXPIRED 거부로 실패했다. 새 마이그레이션과 미확정 예약 정리를 추가한 뒤 통과했다. 실제 Outbox flush 뒤 예외를 주입한 롤백, 유실 응답·재시작·동시 lease·기한 경계 검증을 포함한다.
- Spring AOP spy의 MANDATORY 트랜잭션 설정·컴파일 import/인수·세션 타입 문제는 테스트 픽스처 오류로 수정했다. CSRF 검증은 Redis 없는 가짜 쿠키의 503을 기대값으로 삼지 않고, 실제 격리 Redis 세션으로 403과 정상 토큰 전달을 검증했다.
- 기존 JDBC 픽스처 두 곳의 로컬 KST 의존을 보정했다. 재조회 일정 입력은 UTC LocalDateTime으로, 과거 마이그레이션의 DATETIME 비교는 LocalDateTime을 UTC Instant로 변환한다. 업무 시간을 바꾸거나 테스트 JVM을 UTC로 고정해 숨기지 않았다. 최종 직전 Payment 전체 226건 중 이 비교 1건만 실패한 XML은 `full-before-utc-read`에 보존했다.

최종 정상 명령은 **OpenAPI 갱신 옵션 없이** 성공했다. DOCKER_HOST는 `/Users/wltjq12039035/.orbstack/run/docker.sock`을 사용했다.

```bash
./gradlew :apps:order:test :apps:payment:test :apps:gateway:test \
  :apps:order:integrationTest :apps:payment:integrationTest :apps:gateway:integrationTest \
  :apps:order:bootJar :apps:payment:bootJar :apps:gateway:bootJar --console=plain
```

| 모듈 | 단위 | 실제 격리 인프라 통합 | 실패·오류·건너뜀 |
|---|---:|---:|---:|
| Order | 346 | 188 | 0 |
| Payment | 337 | 226 | 0 |
| Gateway | 120 | 2 | 0 |
| 합계 | 803 | 416 | 0 |

총 **1,219건**이다. 각 모듈 마지막 전체 JUnit XML을 기준으로 하며 집중 실행·재실행을 중복 합산하지 않았다. 최종 명령은 보정한 Payment 통합 전체를 다시 실행했고, 변경 없는 통과 태스크는 Gradle의 UP-TO-DATE 결과를 재사용했다. 신규 집중 시나리오는 승인 복구 17건·Order 생명주기 18건·실제 Redis 세션 승인 1건이며 위 전체 수치에 포함된다.

세 모듈 실행 JAR 빌드가 성공했다. SHA-256은 다음과 같다.

- Order: `1657f03b72477aa3b782c4de3cd3f80300287c17e8ed1fa5c551c696b0c6143a`
- Payment: `2a66cf2d19082c14daedbc73621189699768689512f6fa6490c1e33b22de1404`
- Gateway: `903ee67a6acddbe281cdf59a5bb04a5408c32212bfa9130797258113de958b14`

최종 환경은 `orbstack`, 컨테이너 0·볼륨 0으로 원래 빈 상태를 유지했다. 테스트 종료 중 연결 정리 로그를 실패로 합산하지 않는다. JUnit XML·정상/초기 실패 로그·변경 소스/명세 SHA·JAR SHA와 요약은 `runs/payment-confirmation-20261029/final`에 보존했다. 이 검증 뒤 업무 소스는 수정하지 않고 결과 문서만 갱신했다.

다음 G1 작업은 Order 만료 결과의 Payment 전달·이탈 사본과 불필요 조회 종료다. 이어 실제 TEST 채널 수동 승인 설정과 새 주문의 성공·실패 재시도·만료·응답 유실 전체 여정을 검증할 범위를 정한다. 실제 채널·거래·기존 실행 환경은 이번 검증에서 변경하지 않았다. 선물·보관함이나 별도 금융 구제 정책을 선반영하지 않는다.


## 후속: Order 만료 전달

위 1,219건과 해시는 서버 승인 연결 단계의 당시 검증이다. 이후 [Order 만료 결과 전달](order-expiry-propagation-plan.md)에서 OrderExpired Outbox·Payment의 원구매 대조/주문별 잠금·늦은 OrderPlaced 처리와 조회 예약 종료를 추가했다. 최신 소스와 검증은 후속 기록을 따른다. 위에서 남은 범위로 기재한 만료 사본·조회 정리는 이 후속 구현에서 연결하며, 실제 TEST 채널·거래·전체 사용자 여정은 여전히 별도 범위다.
