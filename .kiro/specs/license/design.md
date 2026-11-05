# 설계: License

## 아키텍처 위치

- 포트 8084, 저장소 MySQL.
- `core/{domain, port, service}` + `api/{controller, listener}` + `adapter/` + `config` 경계를 따른다.
- 리스너는 역직렬화·위임만 하고 트랜잭션·멱등 가드는 서비스가 소유한다(결정 6). 그래서 License는 리스너에 `@Transactional`을 두지 않는다.
- Gateway(8080)가 `/api/v1/library/**`를 라우팅하고 MEMBER 조건을 적용하며, License도 `ApiJwtSecurity`로 서명·issuer·만료·`aud=esd-api`·MEMBER를 재검증한다.
- License는 Payment·Order·Catalog·Auth의 업무 DB를 읽지 않고, 지급 트랜잭션 안에 외부 HTTP 호출을 두지 않는다.

## 핵심 설계 결정

- **권한 하나에 출처 전체를 통합한다(V5).** `(buyerSubject, productId)`별 보유권 한 개의 행에 OrderPaid 입력 전체를 함께 저장한다. 별도 출처 엔티티·테이블을 두지 않는다. 구 `license_purchase`는 과거 자료로만 보존한다.
- **다른 주문의 같은 상품은 충돌이다.** 1:1 모델이므로 같은 구매자·상품에 구매 근거를 추가하지 않는다. 정상 흐름의 중복 구매 예방은 Order 책임이고, License의 유일 제약은 과거 메시지·잘못된 입력에 대한 마지막 방어다.
- **수신→결과를 한 트랜잭션으로 묶는다.** OrderPaid·LicenseSuspensionRequested·LicenseRestorationRequested 수신 시 업무 변경·Inbox·결과 Outbox를 같은 트랜잭션에 저장한다(Transactional Inbox/Outbox).
- **운영 HTTP 없음.** 지급·회수·결과 주입 API를 두지 않는다. 지급 실패는 공통 재시도·DLT로 보존하고 자동 환불을 만들지 않는다.
- **조회는 본인 자료만.** 조회 주체는 `CurrentSubject`의 JWT sub다. 소유자 쿼리·헤더 위조는 무효다.
- **외부 입력 검증은 mapper에서, 내부 입력 검증은 생성 시점에.** `LicenseOrderMapper`가 봉투·본문·키를 검증하고 내부 `PaidOrder`로 변환한다. PaidOrder는 값 자체가 유효한 내부 입력으로 유지하며 JPA 엔티티로 바꾸지 않는다.

## 데이터 모델

| 모델·테이블 | 저장할 값 |
|---|---|
| License / `product_license` | 내부 id, 공개 `licenseId`(UUID), buyerSubject, productId, productCode, 최초 제목, acquiredAt, 이용권 상태(ACTIVE/SUSPENDED) + 출처 필드(eventId, occurredAt, orderNo, requestEventId, paymentResultEventId, paymentId, contentVersion, amount, currency, receivedAt) |
| 정지·복구 이력 | 이용권 상태 전이(정지·복구) 기록 |
| `license_purchase` | V4까지의 구 출처 자료. V5 전환 뒤 읽기·쓰기 없이 과거 자료로만 보존 |
| `processed_event` (Inbox) | `license-paid-order` 등 소비 그룹의 수신 멱등 기록. 업무 변경과 같은 트랜잭션 |
| Outbox | `LicenseGranted`·`LicenseSuspended`·`LicenseRestored` 결과 이벤트 |

제약·인덱스: `licenseId` 유일, `(buyer_subject, product_id)` 유일, `(buyer_subject, product_code)` 유일, 본인 목록용 `(buyer_subject, acquired_at, id)` 인덱스.
출처 식별자(orderNo·eventId·requestEventId·paymentResultEventId·paymentId)는 각각 `product_license`에서 유일하다.
기존 V1~V4 마이그레이션·체크섬을 보존하고 V5에서 출처를 권한 행에 통합한다. buyerSubject는 `utf8mb4_bin`, UUID·productCode·currency는 ASCII binary로 정확히 비교한다.

## API 계약

| 메서드 | 경로 | 역할 | 동작 |
|---|---|---|---|
| GET | `/api/v1/library?page=0&size=20` | MEMBER | 내 보유 상품 페이지(`acquiredAt DESC, id DESC`) |
| GET | `/api/v1/library/{productCode}` | MEMBER | 본인이 보유한 상품 한 개 |
| — | 공개 health·API 문서 | 공개 | 기본 거부, 두 GET과 함께만 허용 |

응답은 `ApiResponse<LicensePage>`와 `LicenseResponse`를 사용한다. 상품 응답은 `licenseId`·`productId`·`productCode`·`title`·`acquiredAt`이다.
익명 401, MEMBER 없음 403, 타인/미보유 404, 잘못된 페이지·상품 코드 형식 400이다.

## 이벤트 계약

| 방향 | 이벤트 | 비고 |
|---|---|---|
| 수신 | `OrderPaid` | `stove.order.v1`. Order가 최초 PAID에 함께 저장한 상품·결제 스냅샷. 소비 그룹 `license-paid-order` |
| 수신 | `LicenseSuspensionRequested` | 환불 흐름의 정지 요청 |
| 수신 | `LicenseRestorationRequested` | 환불 반려 등의 복구 요청 |
| 발행 | `LicenseGranted` | 지급 완료. 지급과 같은 트랜잭션의 Outbox |
| 발행 | `LicenseSuspended` | 정지 완료. 정지와 같은 트랜잭션의 Outbox |
| 발행 | `LicenseRestored` | 복구 완료. 복구와 같은 트랜잭션의 Outbox |

`OrderPlaced`와 그 밖의 비대상 종류는 처리하지 않는다. 결과 이벤트는 Outbox 릴레이(at-least-once)로 발행한다.

## 외부 연동

- 외부 업무 포트 없음. 조회는 License의 자기 자료만 사용한다.
- Download가 같은 사용자 토큰으로 상세를 조회해 `ACTIVE`를 확인한다(소비 측 계약). License는 Download에 사본을 밀어넣지 않는다.
- Order가 지급 확인(`LicenseGranted` 소비)과 환불 정지 요청의 기준으로 License의 결과 이벤트를 사용한다.

## 지급·조회 동작 요약

- `GrantLicense.execute(PaidOrder)`를 READ_COMMITTED 트랜잭션으로 처리한다. orderNo로 기존 권한을 찾아 입력 전체를 대조하고, 출처 ID 재사용·상품 ID/코드 불일치·다른 주문의 같은 상품을 충돌로 거부한다. 신규면 Inbox 접수 뒤 출처를 포함한 License 한 행을 생성·커밋한다.
- 동시 최초 지급은 유일 제약이 최종 방어한다. 경합에서 진 트랜잭션은 전체 롤백하고 Kafka 재전달의 새 트랜잭션에서 수렴한다. 전역 잠금·분산 임대·메모리 중복 집합을 두지 않는다.
- 정지·복구는 원구매와 요청을 대조한 뒤 상태 전이·이력·Inbox·결과 Outbox를 함께 커밋한다. 정지된 상품도 조회에는 남는다.
- Order PAID와 License 지급 사이에는 전달 지연이 있다. 조회는 권한을 생성하거나 Kafka 처리를 재촉하지 않는다.

## 오류·복구

- 권한/구매 근거/Inbox 저장 실패는 함께 롤백하고 공통 Kafka 재시도의 새 트랜잭션에서 재처리한다.
- 커밋 직후 ACK 전 종료는 재전달 입력 전체를 비교해 기존 licenseId·획득 시각을 유지한다.
- 처리 불가 입력·충돌·재시도 소진은 공통 DLT에 보존한다(지급 완료·결제 실패로 해석하지 않음).
- DEAD/DLT로 남은 기존 이벤트는 Outbox DEAD 재큐잉·원본 토픽 재투입(ADMIN 운영 API, P3 A 검증)으로 복구하며 원래 ID·본문을 보존한다. DB 유실 뒤 전량 재구축은 이 복구와 구분한다.
