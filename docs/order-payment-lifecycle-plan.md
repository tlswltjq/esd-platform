# 주문 만료·서버 승인 진행 — Order 기반 구현

2026-10-29. 정책의 정본은 [결정 32](decisions.md#order-expiry-approval-admission), 전체 범위는 [일반화 G1](platform-generalization-plan.md#g1에서-바로-시작할-구체적-작업)을 따른다.

## 이번 검증 단위

Order가 서버 승인 진행을 확정하는 트랜잭션과 만료 경계를 먼저 구현한다. Payment의 PG 승인 API 호출과 브라우저·Gateway 연결은 다음 검증 단위다. 이 기반 구현을 실제 PortOne 수동 승인 전환이나 G1 전체 완료로 표시하지 않는다.

| 상태 | 의미·처리 |
|---|---|
| PENDING | 승인 진행 확정 전 대기. 최초 생성 + 30분까지 새 승인 진행 가능 |
| PAYMENT_PROCESSING | 서버가 승인 진행을 확정·기록. 시각 경과만으로 만료하지 않으며 기존 구매 슬롯 유지 |
| PAID | 검증된 결제 성공. 기한 내 확정된 진행은 늦은 실제 승인·통지에도 기존 조건으로 완료 |
| EXPIRED | 승인 진행 없이 안전하게 만료. 새 승인 진행 거절, 새 requestId의 현재 조건 주문 가능 |
| FAILED | 기존 최종 결제 실패의 의미 유지. PortOne 개별 시도 실패와 구분 |

기한은 `createdAt + 30분`으로 계산하고 주문 조회에 `expiresAt`을 제공한다. 기한 직전 마이크로초까지 접수할 수 있고 기한 시각부터 새 접수를 거절한다. 조회·재시도·요청 재전송으로 기한을 연장하지 않는다. 승인 영수증에는 그 진행의 원래 만료 시각도 저장한다.

## 승인 진행 확정 계약

`POST /internal/v1/orders/{orderNo}/payment-admissions`

- Payment 전용 `X-Payment-Service-Key`를 검사한다. 설정은 Order config의 `stove.order.payment-service-key`에서 조립한다. 키가 비어 있으면 거절하고 MEMBER Bearer만으로 접근할 수 없다. Gateway 공개 경로를 추가하지 않는다.
- 본문: `admissionId`, `requestEventId`, `paymentId`, `buyerSubject`, `amount`, `currency`. PG `paymentToken`이나 비밀값을 Order로 전달하는 계약이 아니다. Payment의 PG 인증 검증과 최종 승인 호출은 이 단계에서 구현하지 않았다.
- Order 잠금 뒤 서버 시각을 읽고 구매자·원래 주문 이벤트·청구액·통화를 대조한다. 단순히 상태가 PENDING이라는 이유로 기한 후 새 진행을 받지 않는다.
- 접수 시 `PAYMENT_PROCESSING`·paymentId 연결과 불변 `order_payment_admission` 영수증을 같은 트랜잭션에 저장한다. 성공 응답은 no-store이며 admissionId·orderNo·paymentId·admittedAt·expiresAt을 반환한다. 실제 PG 송신이나 성공의 증거가 아니다.
- 같은 admissionId·전체 입력의 재전송은 만료 시각이나 PAID 이후에도 최초 영수증을 반환한다. 다른 내용으로 같은 ID를 사용하거나 진행 중인 주문에 다른 새 진행을 요청하면 409다.
- 정상 주문의 기한이 지났다면 EXPIRED 변경을 먼저 커밋하고 HTTP 409 `ORDER_EXPIRED`를 반환한다. 컨트롤러의 거절이 서비스 트랜잭션을 롤백해 만료 변경을 잃지 않게 한다. 잘못 연결된 구매 근거는 만료도 일으키지 않는다.
- 만료 서비스와 승인 진행은 같은 주문 잠금을 사용한다. `PAYMENT_PROCESSING`은 만료 대상이 아니며, 늦은 성공은 연결된 paymentId와 대조한다. EXPIRED의 접수 근거 없는 결과는 구매 성공으로 반영하지 않는다. 해당 비정상 금융 사실의 운영 대응을 이 단계에서 자동 취소로 확정하지 않는다.

V24는 승인 영수증 테이블과 구매 제한 생성 열을 추가/변경한다. `PENDING`, `PAYMENT_PROCESSING`, `PAID`가 동일 구매자·상품의 한 슬롯을 공유하고 `EXPIRED`는 슬롯을 해제한다. 서비스 검사와 MySQL 유일 키를 함께 적용한다.

## 실행·검증 기록

- TDD 도메인 9건 중 8건의 실제 실패를 확인한 뒤 9건을 통과했다. 승인 진행 중 다른 주문이 생성되는 서비스 결함 1건을 별도로 Red로 재현해 수정했다. 원자료는 `runs/order-payment-lifecycle-20261029/red-domain`, `red-purchase-guard`다.
- 초기 전체 단위 실행의 아키텍처 네이밍 위반은 기존 규칙에 맞는 RequestOrderPaymentProcessing·CloseExpiredOrder로 수정했다. 업무 Red와 구분한다.
- 최초 통합 실행은 Testcontainers가 `/var/run/docker.sock`을 찾지 못해 컨텍스트 시작 전에 실패했다. OrbStack의 DOCKER_HOST를 명시한 후 실제 MySQL·Kafka를 기동했다. 이 환경 오류는 업무 Red가 아니다.
- 새 MySQL/HTTP 집중 12건: 같은 명령·다른 명령·다른 주문에서 ID 재사용의 동시성, 실제 flush 뒤 원자 롤백, 기한 도달의 커밋/HTTP 거절, 서비스·DB 구매 제한, 늦은 성공의 OrderPaid 발행, EXPIRED 비정상 결과의 Inbox/Outbox 롤백, 내부 서비스 키·소유/원구매 대조·입력 검증·영수증 재조회가 통과했다. `focused-mysql`을 따른다.
- API 명세 갱신 집중 실행 뒤 기존 공개 14개 연산을 보존하고 OrderResponse의 expiresAt 및 상태 2개만 추가됐음을 대조했다. 내부 API는 공개 OpenAPI에서 숨겼다. `schema-update` 실행은 전체 단위·통합 재실행 수치로 합산하지 않는다.
- 최초 전체 통합 182건 중 91건은 추가한 Clock 빈과 기존 환불 테스트의 타입 기준 Clock 대체가 충돌해 컨텍스트 시작에 실패했다. 새 기능도 기존 모듈 UTC Clock을 주입받도록 config를 정리하고 전체를 다시 실행했다. 이 실패를 별도 금융 업무 Red로 세지 않는다.
- 최종 전체 검사와 빌드 결과는 아래 최종 검증 절에 기록한다. 로컬 테스트에 새 격리 DB와 픽스처를 사용했다. OCI 접근·실제 PG 승인/취소·기존 검증 런타임 갱신은 수행하지 않았다.

## 기반 단계 완료 당시 연결하지 않은 범위

- Payment의 `core.port`에 Order 승인 진행 계약을 정의하고 HTTP adapter·config에서 연결.
- 브라우저 인증 결과와 PortOne 승인 토큰의 안전한 접수·내구 보관, Gateway 경로·본인 인증.
- PortOne 공식 confirm API의 금액/거래 검증·멱등·타임아웃·결과 조회/재송신 계약에 맞춘 실행·재개.
- 검증된 개별 시도 실패 뒤 주문의 기한 내 재진입과 기한 후 종료 조율. 이미 합의한 같은 주문 재결제 정책을 유지한다.
- 만료 대상의 자동 스캔·구매/조회 재진입 연결. 이번 CloseExpiredOrder는 트랜잭션 유스케이스이며 주기 스케줄러를 켜지 않았다. Payment 연동 전 기존 자동 승인 흐름을 임의로 만료시키지 않는다.
- 전체 수동 승인 모드의 성공 반영에는 접수 근거가 필요하도록 연결. 현재 완료된 모의 PG·이벤트 픽스처의 기존 PENDING 결과 반영 계약은 이번 기반 단계에서 제거하지 않았다. 이 사실을 실제 수동 승인 전체 보장의 완료로 해석하지 않는다.
- 실제 TEST 채널 수동 승인 설정·새 거래를 이용한 전체 여정. 실제 금융 동작은 해당 단계에서 합의한 사례에서만 수행한다.

기존 자동 승인 주문을 위한 영구 호환 경로는 제품 요구가 아니다. 과거 개발 주문은 정리하고 새 정책으로 통일한다는 B안을 유지한다. 이번 로컬 기준에는 기존 컨테이너·볼륨이 없었으므로 삭제 실행은 없었다. 향후 선물·보관함 정책은 현재 구매 제한·자동 지급·환불 흐름에 선반영하지 않는다.

## 최종 검증 완료

2026-10-29 로컬 OrbStack에서 다음 명령을 **OpenAPI 갱신 옵션 없이** 실행해 성공했다. `DOCKER_HOST`는 로컬 OrbStack 소켓을 지정했다.

```bash
./gradlew :apps:order:test :apps:order:integrationTest :apps:order:bootJar --console=plain
```

- 단위 **346건**, 실제 MySQL/Kafka 통합 **182건**, 합계 **528건**. 실패·오류·건너뜀은 모두 0이다. 새 집중 검사·명세 갱신 실행을 이 수치에 중복 합산하지 않는다.
- V24 마이그레이션·기존 과거 버전 이행 검사·기존 환불/심사/지급/이벤트 경합과 공개 명세 회귀를 포함한다. 최신 상태 두 개에서도 같은 주문 생성 요청이 기존 주문을 반환하는 검사를 포함했다.
- 실행 JAR 빌드 성공. SHA-256: `28990282c8e9a75dd7edb90e4cf40ffe3cef03379cd4cc42d41c9be39402e325`.
- 검증 전후 로컬 컨테이너 **0→0**, 볼륨 **0→0**. 초기 환경 오류와 Clock 충돌 원자료도 보존했다. 종료 중 컨테이너가 내려가며 나오는 기존 연결 정리 로그를 테스트 실패로 합산하지 않는다.
- 최종 JUnit XML·코드/명세 파일 해시·JAR 해시·환경/범위 요약은 `runs/order-payment-lifecycle-20261029/final/{test,integrationTest,source-sha256.json,validation-summary.json}`에 보존했다.

**완료 범위는 Order 기반 단계다.** Payment/PortOne confirm 실행·브라우저/Gateway·자동 만료 스캔·실제 TEST 거래·G1 전체 완료는 위 미연결 범위로 유지한다. 이 검증 뒤 업무 소스는 수정하지 않았고 결과 문서만 갱신했다.


## 이후 서버 승인 연결

위 528건과 JAR 해시는 Order 기반 단계 당시의 기록이다. 이후 [Payment 서버 승인 연결](payment-confirmation-plan.md)에서 V25 실패 종료 영수증, V26 만료 스캔 인덱스와 자동 스캔을 추가했다. 해당 후속 구현은 새 회귀·빌드 결과로 검증하며 위 해시를 최신 소스의 증거로 사용하지 않는다. 기한 내 개별 실패는 PENDING, 기한 후 실패는 EXPIRED로 돌아가고, 과거 실패 종료 영수증의 재전송은 이후 승인 진행을 변경하지 않는다.
