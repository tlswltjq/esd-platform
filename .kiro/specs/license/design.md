# 설계: License

## 아키텍처 위치

- 포트 8084, 저장소 MySQL.
- `core/{domain, port, service}` + `api/{controller, listener}` + `adapter/` + `config` 경계를 따른다.
- 리스너는 역직렬화·위임만 하고 트랜잭션·멱등 가드는 서비스가 소유한다(결정 6). 그래서 License는 리스너에 `@Transactional`을 두지 않는다.
- Gateway(8080)가 `/api/v1/library/**`·`/api/v1/storage/**`를 라우팅하고 MEMBER 조건을 적용하며, License도 `ApiJwtSecurity`로 서명·issuer·만료·`aud=esd-api`·MEMBER를 재검증한다.
- License는 Payment·Order·Catalog·Auth의 업무 DB를 읽지 않고, 지급 트랜잭션 안에 외부 HTTP 호출을 두지 않는다.

## 핵심 설계 결정

- **구매 근거와 보유권을 나눈다(V10, 결정 93·95).** `product_license`는 주문 1건의 구매 근거(보관함 항목)이고 OrderPaid 입력 전체를 담는다. 보유권은 등록할 때마다 `license_registration`에 새 행으로 생긴다. 구 `license_purchase`는 과거 자료로만 보존한다.
- **구매는 막지 않고 등록에서 막는다(결정 89).** 같은 상품의 다른 주문도 보관함에 들어간다. "이미 가진 상품"은 등록 때 같은 고객·상품의 `ACTIVE` 보유권 유무로 판단하고, `(buyer_subject, product_id, active_guard)` 유일 제약이 마지막 방어다.
- **정지·복구·환불 완료는 구매 근거 단위다(결정 94·96).** 등록·복구는 같은 고객·상품의 구매 근거 전체를 id 순서로 잠가 "쓸 수 있는 보유권 1개" 판단을 직렬화한다. 모든 이벤트는 주문번호·구매 근거 ID로 대상을 찾으므로 이전 주문의 지각 이벤트가 새 보유권에 닿지 않는다.
- **수신→결과를 한 트랜잭션으로 묶는다.** OrderPaid·LicenseSuspensionRequested·LicenseRestorationRequested 수신 시 업무 변경·Inbox·결과 Outbox를 같은 트랜잭션에 저장한다(Transactional Inbox/Outbox).
- **운영 HTTP 없음.** 지급·회수·결과 주입 API를 두지 않는다. 지급 실패는 공통 재시도·DLT로 보존하고 자동 환불을 만들지 않는다.
- **조회는 본인 자료만.** 조회 주체는 `CurrentSubject`의 JWT sub다. 소유자 쿼리·헤더 위조는 무효다.
- **외부 입력 검증은 mapper에서, 내부 입력 검증은 생성 시점에.** `LicenseOrderMapper`가 봉투·본문·키를 검증하고 내부 `PaidOrder`로 변환한다. PaidOrder는 값 자체가 유효한 내부 입력으로 유지하며 JPA 엔티티로 바꾸지 않는다.

## 데이터 모델

| 모델·테이블 | 저장할 값 |
|---|---|
| License(구매 근거) / `product_license` | 내부 id, 공개 `licenseId`(UUID, 주문 1건당 하나), buyerSubject, productId, productCode, 최초 제목, acquiredAt(구매 근거 생성 시각), 상태(STORED/REGISTERED/SUSPENDED/REFUNDED)·suspendedFrom·현재 정지 요청 ID·환불 완료 정지 요청 ID·refundedAt + 출처 필드(eventId, occurredAt, orderNo, requestEventId, paymentResultEventId, paymentId, contentVersion, amount, currency, receivedAt) |
| LicenseRegistration(보유권) / `license_registration` | registrationId, licenseId(구매 근거), orderNo, buyerSubject, productId, productCode, title, 상태(ACTIVE/SUSPENDED/ENDED), registeredAt, endedAt |
| 정지·복구 이력 | 정지·복구 기록. 복구·승인 정정 이력은 `returned_to_storage`를 남긴다 |
| `license_purchase` | V4까지의 구 출처 자료. V5 전환 뒤 읽기·쓰기 없이 과거 자료로만 보존 |
| `processed_event` (Inbox) | `license-paid-order` 등 소비 그룹의 수신 멱등 기록. 업무 변경과 같은 트랜잭션 |
| Outbox | `LicenseGranted`·`LicenseSuspended`·`LicenseRestored` 결과 이벤트 |

제약·인덱스: `licenseId` 유일, 고객·상품 잠금용 `(buyer_subject, product_id, id)`, 보관함용 `(buyer_subject, status, acquired_at, id)`. 보유권은 `registration_id` 유일, `(buyer_subject, product_id, active_guard)` 유일(ACTIVE만), `(license_id, open_guard)` 유일(구매 근거당 열린 보유권 1개). V10에서 고객·상품당 1행 유일 제약을 지웠다.
출처 식별자(orderNo·eventId·requestEventId·paymentResultEventId·paymentId)는 각각 `product_license`에서 유일하다.
기존 V1~V4 마이그레이션·체크섬을 보존하고 V5에서 출처를 권한 행에 통합한다. buyerSubject는 `utf8mb4_bin`, UUID·productCode·currency는 ASCII binary로 정확히 비교한다.

## API 계약

| 메서드 | 경로 | 역할 | 동작 |
|---|---|---|---|
| GET | `/api/v1/library?page=0&size=20` | MEMBER | 내 보유 상품 페이지(`acquiredAt DESC, id DESC`) |
| GET | `/api/v1/library/{productCode}` | MEMBER | 본인이 보유한 상품 한 개(ACTIVE 우선, 없으면 최근 SUSPENDED) |
| GET | `/api/v1/storage?page=0&size=20` | MEMBER | 내 보관함 페이지(`acquiredAt DESC, id DESC`). 항목마다 `registrable` |
| POST | `/api/v1/storage/{licenseId}/registration` | MEMBER | 보관함 항목을 라이브러리에 등록. 이미 보유 409 `PRODUCT_ALREADY_OWNED`, 등록 불가 상태 409 `PURCHASE_NOT_REGISTRABLE` |
| — | 공개 health·API 문서 | 공개 | 기본 거부, 위 API와 함께만 허용 |

응답은 `ApiResponse<LicensePage>`와 `LicenseResponse`를 사용한다. 상품 응답은 `licenseId`·`productId`·`productCode`·`title`·`acquiredAt`·`status`(ACTIVE/SUSPENDED)이고, 페이지는 `content`·`page`·`size`·`totalElements`·`totalPages`를 제공한다. `licenseId`는 공개 조회용 UUID이며 CD키·다운로드 토큰 같은 비밀 값이 아니다.
익명 401, MEMBER 없음 403, 타인/미보유 404, 잘못된 페이지·상품 코드 형식 400이다.

## 이벤트 계약

| 방향 | 이벤트 | 비고 |
|---|---|---|
| 수신 | `OrderPaid` | `stove.order.v1`. Order가 최초 PAID에 함께 저장한 상품·결제 스냅샷. 소비 그룹 `license-paid-order` |
| 수신 | `LicenseSuspensionRequested` | 환불 흐름의 정지 요청 |
| 수신 | `LicenseRestorationRequested` | 환불 반려 등의 복구 요청 |
| 수신 | `LicenseApprovalCorrectionRequested` | PG 송신 전 승인 중단에 따른 정지 해제 요청 |
| 수신 | `PurchaseRefunded` | Order의 환불 완료 확정. 소비 그룹 `license-purchase-refund`. 구매 근거 `REFUNDED`·보유권 `ENDED` |
| 발행 | `LicenseGranted` | 구매 근거(보관함 항목) 생성. Order의 지급 확인 기준. 같은 트랜잭션의 Outbox |
| 발행 | `LicenseSuspended` | 정지 완료. 정지와 같은 트랜잭션의 Outbox |
| 발행 | `LicenseRestored` | 복구 완료. `returnedToStorage`(보관함 반환 여부) 포함. 같은 트랜잭션의 Outbox |
| 발행 | `LicenseApprovalCorrected` | 승인 정정 복구 완료. `returnedToStorage` 포함. 같은 트랜잭션의 Outbox |

### OrderPaid 입력 필드 (13개, Kafka 키·Outbox aggregateId = orderNo)

| 필드 | 값 규칙 |
|---|---|
| eventId·requestEventId·paymentResultEventId·paymentId | 공백 아님, 최대 64자. requestEventId는 원 OrderPlaced.eventId, paymentResultEventId는 반영된 OrderPaymentCompleted.eventId |
| occurredAt | Order가 최초 PAID를 반영한 시각. UTC Instant·마이크로초 정밀도 |
| orderNo | 주문 UUID |
| buyerSubject | 공백 아님, 최대 100자 |
| productId·contentVersion | 양수 |
| productCode | `[A-Za-z0-9][A-Za-z0-9._-]{0,49}` |
| title | 공백 아님, 최대 200자 |
| amount·currency | Order가 확정하고 결제 결과와 대조한 고객 청구액(양수 정수, 판매자 수령액 아님)·KRW |

별도 status 필드는 없다. 값은 Order의 저장 값이며 현재 Catalog 값으로 교체하지 않는다.
`acquiredAt`은 License의 최초 지급 시각으로 `occurredAt`과 구분한다. 같은 입력 재전달은 `receivedAt`도 갱신하지 않는다. 시각은 DATETIME(6)로 왕복 동일 비교한다.
도메인·`GrantLicense`는 EventEnvelope·OrderPaidEvent를 입력받지 않고, 공통 값 규칙은 모듈 내부 `LicenseValues`에 둔다.

`OrderPlaced`와 그 밖의 비대상 종류는 처리하지 않는다. 결과 이벤트는 Outbox 릴레이(at-least-once)로 발행한다.

## 외부 연동

- 외부 업무 포트 없음. 조회는 License의 자기 자료만 사용한다.
- Download가 같은 사용자 토큰으로 상세를 조회해 `ACTIVE`를 확인한다(소비 측 계약). License는 Download에 사본을 밀어넣지 않는다.
- Order가 지급 확인(`LicenseGranted` 소비)과 환불 정지 요청의 기준으로 License의 결과 이벤트를 사용한다.

## 지급·조회 동작 요약

- `GrantLicense.execute(PaidOrder)`를 READ_COMMITTED 트랜잭션으로 처리한다. orderNo로 기존 구매 근거를 찾아 입력 전체를 대조하고, 출처 ID 재사용을 충돌로 거부한다. 신규면 Inbox 접수 뒤 출처를 포함한 구매 근거 한 행을 `STORED`로 생성·커밋한다.
- `RegisterLicense`는 고객·상품 구매 근거를 잠근 뒤 `ACTIVE` 유무를 보고 새 보유권을 만든다. `ApplyPurchaseRefund`는 정지 요청 ID가 맞을 때만 구매를 끝낸다.

| 구매 근거 이전 상태 | 복구 시 다른 ACTIVE | 복구 결과 |
|---|---|---|
| STORED | — | STORED |
| REGISTERED | 없음 | REGISTERED, 보유권 ACTIVE |
| REGISTERED | 있음 | STORED, 옛 보유권 ENDED, returnedToStorage=true |
- 동시 최초 지급은 유일 제약이 최종 방어한다. 경합에서 진 트랜잭션은 전체 롤백하고 Kafka 재전달의 새 트랜잭션에서 수렴한다. 전역 잠금·분산 임대·메모리 중복 집합을 두지 않는다.
- 정지·복구는 원구매와 요청을 대조한 뒤 상태 전이·이력·Inbox·결과 Outbox를 함께 커밋한다. 정지된 상품도 조회에는 남고, 환불이 끝난(`REFUNDED`·`ENDED`) 항목은 라이브러리·보관함에서 빠진다(결정 97). 이력은 주문 내역에 남는다.
- Order PAID와 License 지급 사이에는 전달 지연이 있다. 조회는 권한을 생성하거나 Kafka 처리를 재촉하지 않는다.

## 오류·복구

- 권한/구매 근거/Inbox 저장 실패는 함께 롤백하고 공통 Kafka 재시도의 새 트랜잭션에서 재처리한다.
- 커밋 직후 ACK 전 종료는 재전달 입력 전체를 비교해 기존 licenseId·획득 시각을 유지한다.
- 처리 불가 입력·충돌·재시도 소진은 공통 DLT에 보존한다(지급 완료·결제 실패로 해석하지 않음).
- DEAD/DLT로 남은 기존 이벤트는 Outbox DEAD 재큐잉·원본 토픽 재투입(ADMIN 운영 API, P3 A 검증)으로 복구하며 원래 ID·본문을 보존한다. DB 유실 뒤 전량 재구축은 이 복구와 구분한다. 운영 절차는 [License 이벤트 복구 런북](../../../docs/license-event-recovery-runbook.md)을 따른다.

| 중단 지점 | 남는 상태 | 복구 |
|---|---|---|
| 지급 전(OrderPaid 처리 실패·재시도 소진) | Order/Payment PAID, 보유권·지급 Inbox 0건, 원래 OrderPaid가 DLT | 원인 제거 뒤 같은 OrderPaid를 DLT 재투입 → 보유권·Inbox 각 1건 |
| 지급 확인 전(Order의 LicenseGranted 처리 실패) | 보유권 ACTIVE, Order 지급 확인 0건, 대기 중 환불은 PG 취소 없이 대기 | 같은 LicenseGranted를 재투입 → 확인 1건, 대기 환불의 정지 요청 1건 연결 |
| 재투입 중복 | 원래 이벤트 재전달 | 보유권·확인·Inbox·Outbox·정지 요청 각 1건 유지, licenseId·acquiredAt·SUSPENDED 보존 |

- 원래 이벤트를 새 ID로 재생성하거나 업무 행·시각을 수정해 성공 상태를 만들지 않는다. 원본 부족·모순 건은 운영 확인 대상으로 남기고 별도 권한 재구성·자동 재투입·별도 지급 엔진을 두지 않는다.
- 주문·보유권·지급 확인·원본 Outbox/Inbox 연결은 읽기 전용 SQL(`tools/operations/license-recovery.sql`)로 대조한다. 재발행 성공과 최종 업무 완료를 구분한다.

## 과거 자료·전환

- Flyway V1~V4와 구 자료를 유지하고 V5가 1:1 구매 근거를 `product_license`로 복사·출처 제약을 추가한다. V5는 검사 실패 시 출처 컬럼 추가 전에 거부한다.
- 전환 전 점검 SQL `tools/migrations/license/license-merge-preflight.sql`(Order V10·License V4용)을 쓰기 중지 상태에서 실행한다. 통과 조건: 구매자·productId/productCode별 PENDING·PAID 합 ≤ 1, 모든 권한에 출처 정확히 1건, 공통 필드·`license-paid-order` Inbox 대응.
- V10은 기존 `ACTIVE` 행을 `REGISTERED` + `ACTIVE` 보유권으로, `SUSPENDED` 행을 `SUSPENDED`(suspendedFrom=`REGISTERED`) + `SUSPENDED` 보유권으로 옮긴다. 지우거나 고르지 않는다.
- MySQL DDL은 자동 커밋이므로 중간 실패의 자동 롤백을 가정하지 않고 실제 스키마·Flyway 기록을 확인한 뒤 백업 복구 또는 검증한 전진 복구를 한다.
- 구 숫자 member_id·license_key·회수 상태, 구 소비 그룹·Inbox는 새 자료로 변환하지 않는다. 구 Outbox는 릴레이를 끈 채 보존한다. 재작성 전 DB 유실 런북은 새 테이블에 적용하지 않는다.
- 독립 검증 환경의 성공은 공유 환경 자료 전환 완료를 뜻하지 않는다. 공유 환경 적용 전 과거 PAID·미발행 Outbox·Kafka 오프셋과 소급 지급 필요성을 별도로 대조한다.

## 사용 안내

- MEMBER access token으로 `GET /api/v1/library?page=0&size=20`, `GET /api/v1/library/{productCode}`를 호출한다(인증은 Auth·Gateway 스펙 참조).
- Order가 최초 PAID를 반영하면 OrderPaid가 발행되고 License가 보관함 항목을 만든다. 고객은 `GET /api/v1/storage`에서 `orderNo`로 항목을 찾아 `POST /api/v1/storage/{licenseId}/registration`으로 등록한다. 고르지 않으면 보관함에 남는다.
- Order PAID 직후에는 이벤트 전달 중이라 보관함에 아직 없을 수 있다. 클라이언트는 주문 PAID를 보관함 항목 생성으로 간주하지 않고 다시 조회한다.
- 같은 상품을 다시 사도 Order는 막지 않는다(판매 금지만 막음). 이미 `ACTIVE` 보유권이 있으면 새 구매분은 보관함에 남고 등록은 409다.
- 환불로 `SUSPENDED`가 된 상품도 라이브러리(등록 전이면 보관함)에 남는다. Download는 `ACTIVE`일 때만 신규 URL을 제공한다. 기간제·이미 받은 파일 회수는 범위 밖이다.
- 지급 오류는 Payment FAILED나 자동 환불을 뜻하지 않는다. 재투입은 원인 해결 뒤 원래 eventId·키·헤더·본문으로 한다.
- 장애 주입 픽스처는 integrationTest 클래스패스에만 두고 운영 bootJar·업무 HTTP API에 포함하지 않는다.
