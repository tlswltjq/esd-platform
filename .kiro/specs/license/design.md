# 설계: License

> 상태: 작성 중(결정 150·178). 목표 계약이다. 코드가 다른 곳은 [tasks.md](tasks.md) "코드와 맞출 것"에 있다. 줄 번호는 2026-11-09 `docs/spec-foundation` 기준. 경로 줄임 `license:` = `apps/license/src/main/java/com/stove/license/`, `license/db/Vn` = `apps/license/src/main/resources/db/migration/Vn__*.sql`.

## 아키텍처 위치

- 포트 8084, MySQL 스키마 `stove_license`. 다른 모듈 스키마를 읽지 않는다([P1.1-2](../platform/requirements.md)).
- 패키지: `core/{domain, service}`(외부 업무 포트 없음) + `api/{controller, listener}` + `config`. `adapter`는 없다(외부 공급자·다른 모듈 동기 호출 없음).
- 의존 방향([플랫폼 design "모듈 경계와 의존 방향"](../platform/design.md#모듈-경계와-의존-방향)):
  - 동기(받기만): Gateway → License(`/api/v1/library/**`·`/api/v1/storage/**`), Download → License(`GET /api/v1/library/{code}`, 고객 JWT 전달). License는 어떤 업무 모듈도 동기 호출하지 않는다.
  - 비동기: Order ⇄ License "요청 → 사실 응답" 쌍 S4·S5·S7·S8과 단방향 `PurchaseRefunded` → License(목표: 응답 `LicenseRefundApplied`로 쌍이 됨, 결정 158). 받는 토픽 `stove.order.v1`, 내는 토픽 `stove.license.v1`, 키는 모두 `orderNo`.
- 리스너는 봉투 해석·`*Mapper.from` 검증·서비스 위임만 하고 트랜잭션·Inbox·Outbox는 서비스가 소유한다(결정 6). 리스너에 `@Transactional`을 두지 않는다.
- Gateway가 MEMBER 조건으로 라우팅하고, License도 `ApiJwtSecurity`로 서명·issuer·만료·`aud=esd-api`·역할을 다시 검증한다(`license:config/SecurityConfig.java:15-27`).
- 모든 처리 트랜잭션은 `READ_COMMITTED`이고, 실패하면 업무 변경·처리 기록·Inbox·Outbox가 함께 롤백된다.

## 데이터 모델

### 표

| 표 | 뜻 | 칸(요지) | 제약 |
|---|---|---|---|
| `product_license` (엔티티 `License`) | 구매 근거(보관함 항목), 주문 1건당 1행 | `id`, `license_id`(UUID), `buyer_subject`(utf8mb4_bin), `product_id`, `product_code`(ascii_bin), `title`, 출처 13개(`event_id`·`occurred_at`·`order_no`·`request_event_id`·`payment_result_event_id`·`payment_id`·`content_version`·`amount`·`currency` + 위 상품·구매자), `acquired_at`·`received_at`(License 시계), `status`, `suspended_from`, `suspension_request_event_id`, `refunded_suspension_request_event_id`, `refunded_at` | `uk_product_license_id(license_id)`, `uk_product_license_order(order_no)`·`_event(event_id)`·`_request(request_event_id)`·`_result(payment_result_event_id)`·`_payment(payment_id)`(`license/db/V5:60-64`), `chk_product_license_source(content_version>0 AND amount>0 AND currency='KRW')`, `chk_product_license_product(product_id>0)`, `chk_product_license_status`(`license/db/V10:50`), 인덱스 `idx_product_license_product(buyer_subject, product_id, id)`·`idx_product_license_storage(buyer_subject, status, acquired_at, id)` |
| `license_registration` (엔티티 `LicenseRegistration`) | 보유권, 등록 1번당 1행 | `registration_id`(UUID), `license_id`·`order_no`(구매 근거 사본), `buyer_subject`, `product_id`, `product_code`, `title`, `status`, `registered_at`, `ended_at`, 생성 열 `active_guard`·`open_guard` | `uk_license_registration_id`, `uk_license_registration_active(buyer_subject, product_id, active_guard)`, `uk_license_registration_open(license_id, open_guard)`, `fk_license_registration_purchase(license_id → product_license)`, `chk_license_registration_status`, `chk_license_registration_end((status='ENDED') = (ended_at IS NOT NULL))`(`license/db/V10:5-27`) |
| `license_suspension` (`LicenseSuspension`) | 정지 처리 기록(불변) | `request_event_id`, `request_occurred_at`(Order 시각, 값으로만), `refund_id`, `license_id`, 구매 연결, `suspended_at`(License 시계), `completed_event_id`(= `LicenseSuspended.eventId`) | `uk_license_suspension_request`, `uk_license_suspension_result`, `fk_license_suspension_license`(`license/db/V7`) |
| `license_restoration` (`LicenseRestoration`) | 복구 처리 기록(불변) | `request_event_id`, `request_fingerprint`(SHA-256), `request_occurred_at`, `refund_id`, `attempt_id`, `failure_event_id`, `license_id`, `suspension_request_event_id`, `completed_event_id`, `restored_at`, `returned_to_storage` | `uk_license_restoration_request`·`_suspension`·`_result`, `fk_license_restoration_license`·`_suspension`(`license/db/V8`). 시각 CHECK는 V12에서 지웠다(I9) |
| `license_approval_correction` (`LicenseApprovalCorrection`) | 승인 정정 처리 기록(불변) | PK `request_event_id`, `request_fingerprint`, `request_occurred_at`, `stop_request_id`, `suspension_request_event_id`, `license_id`, `completed_event_id`, `restored_at`, `returned_to_storage` | `uk_license_correction_stop`·`_suspension`·`_completed`, FK 둘(`license/db/V9`). 시각 CHECK 없음(V12) |
| `processed_event` | Inbox | `event_id`, `consumer_group`, `event_type` | `uk_processed_event(event_id, consumer_group)`(`license/db/V1:51`) |
| `outbox_event` | 결과 이벤트 Outbox(공통) | 공통 메시징 라이브러리 | `uk_outbox_event_id` |
| `outbox_republish` | `SENT` 재발행 기록(결정 129) | `event_id`, `event_type`, `previous_sent_at`, `republished_by`, `reason`, `republished_at` | `license/db/V11` |
| 실패 보관 표(목표, 공통) | 소비 실패 레코드·키 정지(결정 160) | 공통 메시징 설계 | 플랫폼 T2·T7 |
| `license_purchase` | V4까지의 구 출처. 읽기·쓰기 없음 | — | 보존만 |

### 목표에서 더할 제약 (코드 차이는 tasks LI-T2·LI-T3)

| 제약(가칭) | 내용 | 근거 |
|---|---|---|
| 정지 전 상태 표현 | `[결정 필요 M-LI-1]`의 선택에 따라 ① `chk_product_license_status`가 `STORED`·`REGISTERED`·`SUSPENDED_FROM_STORED`·`SUSPENDED_FROM_REGISTERED`·`REFUNDED`를 허용하고 `suspended_from` 열을 없앤다, 또는 ② `chk_product_license_suspended_from`: `suspended_from IN ('STORED','REGISTERED')` 이고 `(status='SUSPENDED') = (suspended_from IS NOT NULL)` | R12-2, RC-17 |
| `chk_product_license_suspension` | `(status가 정지) = (suspension_request_event_id IS NOT NULL)` | R12-3 |
| `chk_product_license_refund` | `(status='REFUNDED') = (refunded_suspension_request_event_id IS NOT NULL) = (refunded_at IS NOT NULL) = (refunded_event_id IS NOT NULL)` | R12-3 |
| `refunded_event_id`·`refunded_refund_id` 열 + `uk_product_license_refund_event(refunded_event_id)` | 회수에 쓴 `PurchaseRefunded.eventId`·`refundId`. 같은 회수가 다른 `eventId`로 다시 오는 것을 업무 키로 막고(P2.3-3), `LicenseRefundApplied` 본문의 근거가 된다 | R11-1·R11-2, 결정 158 |

## 상태 모델

### 구매 근거 `PurchaseStatus` (`product_license.status`)

| 상태 | 뜻 | 열린 보유권 | 보관함 | 라이브러리 |
|---|---|---|---|---|
| `STORED` | 보관함에 있고 등록하지 않았다 | 없음 | 보임(`registrable` 계산) | — |
| `REGISTERED` | 라이브러리에 등록해 쓸 수 있다 | `ACTIVE` 1개 | 안 보임 | 보임 |
| 정지(정지 전 `STORED`) | 등록 전에 환불로 정지됐다 | 없음 | 보임(`registrable=false`) | — |
| 정지(정지 전 `REGISTERED`) | 등록 뒤 환불로 정지됐다 | `SUSPENDED` 1개 | 안 보임 | 보임(정지 표시) |
| `REFUNDED` | 환불이 끝났다. 끝 상태 | 없음(`ENDED`만) | 안 보임 | 안 보임(결정 97) |

정지 상태의 표현 — `[결정 필요 M-LI-1]`(소급 RC-17): 지금은 `SUSPENDED` 한 값 + `suspended_from` 칸이다. 목표 후보는 ① enum 값 둘(`SUSPENDED_FROM_STORED`·`SUSPENDED_FROM_REGISTERED`) ② `SUSPENDED` + 직교 칸 `suspended_from`(CHECK로 조합 강제, 원칙 ④ "불가피하면 직교 필드"). 아래 표는 두 정지를 다른 열로 적어 어느 안이든 같은 전이다. 표에서 줄여 `정지·S`(정지 전 `STORED`), `정지·R`(정지 전 `REGISTERED`)로 쓴다.

상태에 딸린 식별자(하위 상태가 아니라 그 상태의 근거 값이다. R12-3의 CHECK로 상태와 묶는다): 정지 상태의 `suspension_request_event_id`(현재 정지), `REFUNDED`의 `refunded_suspension_request_event_id`·`refunded_at`·`refunded_event_id`(목표).

#### 구매 근거 상태 × 트리거 (빈칸 0)

칸 읽는 법: `허용 → 다음 상태(부수 효과)` / `무변화` / `거절(오류, 처리)`. `영구`는 재시도 없이 실패 보관·키 정지(P2.6-3·4·5), `재시도`는 재시도 가능 실패(P2.5-3, 소진하면 실패 보관·키 정지). 가드로 갈리는 칸은 `가드 → 결과; 그 밖 → 결과`로 적는다. "연결 일치" = `orderNo`·`buyerSubject`·`productId`·`productCode`·`paymentId`(복구·정정·회수는 `licenseId` 포함)가 구매 근거와 같음.

| 트리거 \ 상태 | (행 없음) | `STORED` | `REGISTERED` | `정지·S` | `정지·R` | `REFUNDED` |
|---|---|---|---|---|---|---|
| **E1** `OrderPaid`(이 `orderNo`) | 출처 4개 ID가 다른 행에 없음·Inbox 첫 수신 → 허용 → `STORED`(새 `licenseId`, `acquiredAt`=`receivedAt`=License 시각, Outbox `PurchaseRecordStored`); 출처 ID 재사용·Inbox만 있음 → 거절(`CONFLICT`, 영구) | 13개 값 같음·Inbox 있음 → 무변화; 그 밖 → 거절(`CONFLICT`, 영구) | 같음 | 같음 | 같음 | 같음 |
| **H1** `POST /api/v1/storage/{licenseId}/registration` | 거절(404 `NOT_FOUND`) | 고객·상품 전체 잠금 뒤 다른 `ACTIVE` 없음 → 허용 → `REGISTERED`(새 보유권 `ACTIVE`); 있음 → 거절(409 `PRODUCT_ALREADY_OWNED`) | 열린 보유권 `ACTIVE` → 무변화(그 보유권 반환) | 거절(409 `PURCHASE_NOT_REGISTRABLE`) | 거절(409 `PURCHASE_NOT_REGISTRABLE`) | 거절(409 `PURCHASE_NOT_REGISTRABLE`) |
| **E2** `LicenseSuspensionRequested` 새 요청(그 `eventId`의 정지 기록 없음) | 거절(`NOT_FOUND`, 재시도) — Order는 `PurchaseRecordStored` 확인 뒤에만 요청한다(결정 29 B) | 연결 일치·Inbox 첫 수신 → 허용 → `정지·S`(정지 기록, Outbox `LicenseSuspended`); 그 밖 → 거절(`CONFLICT`, 영구) | 연결 일치·열린 보유권 `ACTIVE`·Inbox 첫 수신 → 허용 → `정지·R`(보유권 `SUSPENDED`, 정지 기록, Outbox `LicenseSuspended`); 그 밖 → 거절(`CONFLICT`, 영구) | 거절(`CONFLICT`, 영구 — 다른 요청으로 정지 중) | 거절(`CONFLICT`, 영구) | 연결 일치·`refundId`가 회수 근거 정지 기록의 것과 같음(회수가 재신청 정지보다 먼저 처리됨)·Inbox 첫 수신 → 무변화(Inbox만, 정지 기록·Outbox 없음, 결정 179); 그 밖 → 거절(`CONFLICT`, 영구 — 끝난 구매) |
| **E3** `LicenseSuspensionRequested` 재전달(그 `eventId`의 정지 기록 있음) | 거절(`NOT_FOUND`) — 정지 기록은 FK로 행을 가리켜 생기지 않음 | 정지 기록과 본문·`licenseId` 같음·Inbox 있음 → 무변화(결과 재발행 안 함); 그 밖 → 거절(`CONFLICT`, 영구) | 같음 | 같음 | 같음 | 같음 |
| **E4** `LicenseRestorationRequested` 첫 수신 | 거절(`NOT_FOUND`, 재시도) | 거절(`CONFLICT`, 영구 — 현재 정지 없음) | 거절(`CONFLICT`, 영구) | 고객·상품 전체 잠금, 연결 일치, 정지 기록(요청의 정지 ID)의 `licenseId`·`refundId`·`completedEventId`(=`suspensionEventId`) 일치, 현재 정지 = 요청 정지, Inbox 첫 수신 → 허용 → `STORED`(복구 기록 `returned_to_storage=false`, Outbox `LicenseRestored`); 그 밖 → 거절(`CONFLICT`, 영구) | 같은 가드 + 다른 `ACTIVE` 없음 → 허용 → `REGISTERED`(보유권 `ACTIVE`, `returned=false`); 다른 `ACTIVE` 있음 → 허용 → `STORED`(옛 보유권 `ENDED`, `returned=true`); 가드 불일치 → 거절(`CONFLICT`, 영구) | 거절(`CONFLICT`, 영구) |
| **E5** `LicenseApprovalCorrectionRequested` 첫 수신 | 거절(`NOT_FOUND`, 재시도) | 거절(`CONFLICT`, 영구) | 거절(`CONFLICT`, 영구) | E4와 같은 가드 → 허용 → `STORED`(정정 기록, Outbox `LicenseApprovalCorrected`); 그 밖 → 거절 | E4와 같은 세 갈래(정정 기록, Outbox `LicenseApprovalCorrected`) | 거절(`CONFLICT`, 영구) |
| **E6** 복구·정정 요청 재전달(그 `eventId`의 처리 기록 있음) | 거절(`NOT_FOUND`) — FK로 생기지 않음 | 연결 일치·지문 같음·Inbox 있음 → 무변화(결과 재발행 안 함); 그 밖 → 거절(`CONFLICT`, 영구) | 같음 | 같음(다른 정지로 다시 정지돼 있어도 무변화 — 그 정지를 풀지 않음) | 같음 | 같음 |
| **E7** `PurchaseRefunded` 첫 수신 | 거절(`NOT_FOUND`, 재시도) | 늦은 취소 가드 → 허용 → `REFUNDED`(회수 근거, Outbox `LicenseRefundApplied`); 그 밖 → 거절(`CONFLICT`, 영구) | 늦은 취소 가드 → 허용 → `REFUNDED`(열린 보유권 `ACTIVE`→`ENDED`, Outbox `LicenseRefundApplied`); 그 밖 → 거절(`CONFLICT`, 영구) | 요청 정지 = 현재 정지·연결 일치·Inbox 첫 수신 → 허용 → `REFUNDED`(Outbox `LicenseRefundApplied`); 요청 정지가 복구로 끝난 이전 정지·현재 정지와 `refundId` 같음 → 허용 → `REFUNDED`(현재 정지 비움, Outbox `LicenseRefundApplied`, 결정 179); 그 밖 → 거절(`CONFLICT`, 영구) | 요청 정지 = 현재 정지… → 허용 → `REFUNDED`(보유권 `SUSPENDED`→`ENDED`, Outbox `LicenseRefundApplied`); 이전 복구된 정지·현재 정지와 `refundId` 같음 → 허용 → `REFUNDED`(현재 정지 비움, 보유권 `SUSPENDED`→`ENDED`, Outbox `LicenseRefundApplied`, 결정 179); 그 밖 → 거절 | 같은 회수(정지 ID·`refundId` 같음)인데 다른 `eventId` → 거절(`CONFLICT`, 영구 — P2.3-4); 다른 정지 → 거절(`CONFLICT`, 영구) |
| **E8** `PurchaseRefunded` 재전달(같은 `eventId`, Inbox 있음) | 거절(`NOT_FOUND`) | 거절(`CONFLICT`, 영구 — Inbox만 있고 회수 없음, R3-6) | 같음 | 같음 | 같음 | `refunded_event_id`·회수 정지 ID 같음 → 무변화(Outbox 없음) |
| **Q1** `GET /api/v1/library`·`/library/{code}`·`/storage` | 무변화(목록 제외·상세 404) | 무변화 | 무변화 | 무변화 | 무변화 | 무변화(목록 제외) |
| **O1** ADMIN `SENT` 재발행·`DEAD` 회수 | 무변화(Outbox 행 상태만, R7-10) | 무변화 | 무변화 | 무변화 | 무변화 | 무변화 |
| **O2** ADMIN 실패 보관 재투입 | 원래 트리거(E1~E8)의 칸과 같다(같은 리스너 경로, R7-7) | 같음 | 같음 | 같음 | 같음 | 같음 |

- **늦은 취소 가드**(E7, R11-4, 결정 157, MC-L1, 1회차 지적 A2·B2, 판정 전): 연결 일치, `PurchaseRefunded.suspensionRequestEventId`가 이 구매 근거의 `license_suspension` 행이고 그 정지의 `license_restoration` 행이 있다(승인 정정으로 끝난 정지는 PG 송신 전에 멈춘 것이라 해당 없음), 구매 근거가 정지 상태가 아니거나 같은 `refundId`의 재신청 정지로 정지 중이다(결정 179), Inbox 첫 수신. 판정은 식별자만 쓰고 시각을 비교하지 않는다(원칙 ⑥).
- 예약 작업: License에는 상태를 바꾸는 예약 작업이 없다. 정방향 누락은 요청한 Order가 센다(결정 158).
- `REGISTERED` + 열린 보유권 없음, `정지·R` + 열린 보유권 없음은 LI-I5 위반이며 E2·E4·E7에서 `CONFLICT`(영구)로 드러난다(`License.requireCurrent`, `license:core/domain/License.java:140-143`).

### 보유권 `LicenseStatus` (`license_registration.status`)

| 상태 | 뜻 |
|---|---|
| `ACTIVE` | 쓸 수 있다. 고객·상품당 최대 1개(`uk_license_registration_active`). Download가 권한으로 본다 |
| `SUSPENDED` | 환불로 정지됐다. 라이브러리에 정지 표시로 보인다 |
| `ENDED` | 회수 또는 보관함 반환으로 끝났다. 이력으로만 남는다(라이브러리에서 뺀다). `ended_at` 필수 |

보유권은 구매 근거의 메서드 안에서만 바뀐다(`LicenseRegistration.open`·`suspend`·`activate`·`end`는 패키지 공개). 아래 열의 "대상"은 그 구매 근거의 열린 보유권(`LicenseRegistrations.current`)이다. `ENDED` 행은 열린 보유권이 아니므로 어떤 트리거의 대상도 아니다.

| 트리거 \ 상태 | (대상 없음) | `ACTIVE` | `SUSPENDED` | `ENDED` |
|---|---|---|---|---|
| H1 등록(구매 근거 `STORED`, 다른 `ACTIVE` 없음) | 허용 → `ACTIVE`(새 `registration_id`, `registered_at`) | 같은 구매 근거 재요청 → 무변화(반환); 같은 상품 다른 구매 근거의 `ACTIVE` → 거절(409 `PRODUCT_ALREADY_OWNED`) | 거절(409 `PURCHASE_NOT_REGISTRABLE` — 구매 근거가 `정지·R`) | 무변화(대상 아님, 새 행을 만든다) |
| E2 정지(구매 근거 `REGISTERED`) | 거절(`CONFLICT`, 영구 — LI-I5 위반) | 허용 → `SUSPENDED` | 거절(`CONFLICT`, 영구) | 무변화(대상 아님) |
| E2 정지(구매 근거 `STORED`) | 무변화(보유권 없음이 정상) | 불가 — 다른 구매 근거의 보유권이라 무변화 | 무변화(다른 구매 근거) | 무변화 |
| E4·E5 복구·정정, 다른 `ACTIVE` 없음 | 거절(`CONFLICT`, 영구 — `정지·R`인데 대상 없음) | 거절(`CONFLICT`, 영구) | 허용 → `ACTIVE` | 무변화(대상 아님) |
| E4·E5 복구·정정, 다른 `ACTIVE` 있음 | 거절(`CONFLICT`, 영구) | 무변화(그 `ACTIVE`는 다른 구매 근거의 것, 그대로) | 허용 → `ENDED`(`ended_at`, `returned_to_storage=true`) | 무변화 |
| E4·E5 복구·정정(구매 근거 `정지·S`) | 무변화(보유권 없음이 정상) | 무변화(다른 구매 근거) | 무변화(다른 구매 근거) | 무변화 |
| E7 회수(현재 정지, `정지·R`) | 거절(`CONFLICT`, 영구) | 거절(`CONFLICT`, 영구) | 허용 → `ENDED` | 무변화 |
| E7 회수(현재 정지, `정지·S`) | 무변화(보유권 없음이 정상) | 무변화(다른 구매 근거) | 무변화(다른 구매 근거) | 무변화 |
| E7 늦은 취소 회수(구매 근거 `REGISTERED`) | 거절(`CONFLICT`, 영구 — LI-I5 위반) | 허용 → `ENDED`(MC-L1) | 거절(`CONFLICT`, 영구) | 무변화 |
| E7 늦은 취소 회수(구매 근거 `STORED`) | 무변화(보유권 없음이 정상 — 보관함 반환 뒤였을 수 있음) | 무변화(다른 구매 근거) | 무변화(다른 구매 근거) | 무변화 |
| E7 회수, 이전 복구된 정지 + 같은 `refundId`의 재신청 정지 진행 중(구매 근거 `정지·R`) | 거절(`CONFLICT`, 영구 — LI-I5 위반) | 무변화(다른 구매 근거) | 허용 → `ENDED`(결정 179) | 무변화 |
| E7 회수, 이전 복구된 정지 + 같은 `refundId`의 재신청 정지 진행 중(구매 근거 `정지·S`) | 무변화(보유권 없음이 정상) | 무변화(다른 구매 근거) | 무변화(다른 구매 근거) | 무변화 |
| E1·E3·E6·E8·Q1·O1 | 무변화 | 무변화 | 무변화 | 무변화 |

### 처리 기록 (`license_suspension`·`license_restoration`·`license_approval_correction`)

상태 열이 없는 불변 기록이다(R12-4). 요청 한 건을 처리해 위 두 상태를 바꾼 트랜잭션에서 한 행이 생기고, 결과 이벤트의 `eventId`는 기록의 `completed_event_id`다.

| 받은 요청 | 기록 | 기록의 유일 키 | 결과 이벤트 | 소비 그룹 |
|---|---|---|---|---|
| `LicenseSuspensionRequested` | `license_suspension` | 요청 `eventId`, 결과 `eventId` | `LicenseSuspended` | `license-suspension` |
| `LicenseRestorationRequested` | `license_restoration`(지문 SHA-256, `attempt_id`·`failure_event_id`, `returned_to_storage`) | 요청 `eventId`, 정지 요청 ID(정지 1건당 복구 1건), 결과 `eventId` | `LicenseRestored` | `license-restoration` |
| `LicenseApprovalCorrectionRequested` | `license_approval_correction`(PK 요청 `eventId`, `stop_request_id`) | `stop_request_id`, 정지 요청 ID, 결과 `eventId` | `LicenseApprovalCorrected` | `license-approval-correction` |
| `PurchaseRefunded` | 구매 근거의 회수 근거 칸(별도 표 없음) | `refunded_event_id`(목표) | `LicenseRefundApplied`(가칭, 목표) | `license-purchase-refund` |
| `OrderPaid` | 구매 근거 행 자체 | 출처 ID 5개 | `PurchaseRecordStored`(지금 `LicenseGranted`) | `license-paid-order` |

같은 정지에 복구 기록과 정정 기록이 둘 다 생기지 않는 것은 DB가 아니라 `License.restore`의 "현재 정지 = 요청 정지" 가드가 지킨다(첫 복구가 현재 정지를 비움). LI-I7.

### 잠금 범위 (R6-8, 환불 초안 F5)

| 처리 | 잠금 | 이유 |
|---|---|---|
| 등록(H1) | `ProductPurchaseLock.lockOwned` — 같은 고객·상품 구매 근거 전체 `PESSIMISTIC_WRITE`(id 순) 뒤 대상 `refresh` | `ACTIVE`를 새로 만들므로 "다른 `ACTIVE` 없음" 판단을 직렬화 |
| 복구·정정(E4·E5) | `ProductPurchaseLock.lock` — 같음 | `ACTIVE`를 되살리거나 보관함 반환을 고르므로 같은 판단 |
| 정지(E2) | `LicenseRepository.lockByOrderNo` — 대상 한 행 | `ACTIVE`를 만들지 않는다. 등록·복구의 전체 잠금이 이 행을 포함하므로 같은 구매 근거에 대한 정지와 등록·복구는 이 행에서 직렬화된다 |
| 회수(E7, 늦은 취소 포함) | `lockByOrderNo` — 대상 한 행 | `ACTIVE`를 끝내기만 하고 만들지 않는다. 같은 이유로 등록·복구와 직렬화 |
| 구매 근거 생성(E1) | 없음(새 행) | 동시 최초 생성은 `uk_product_license_*`가 막고, 진 트랜잭션은 롤백 뒤 재전달로 수렴 |

마지막 방어는 `uk_license_registration_active`·`uk_license_registration_open`이다. 단일 행 잠금과 id 순 전체 잠금만 쓰므로 교착 순서가 생기지 않는다. 확인 테스트: `PurchaseInterleavingIntegrationTest#suspensionWaitsForTheRegistrationLockAndThenSuspendsTheNewLicense`·`#restorationWaitsForTheNewRegistrationLock`·`#restorationReadBeforeCommittedRefundIsRefused`, `ProductPurchaseLockIntegrationTest#lockedPurchaseReflectsSuspensionCommittedAfterAnEarlierRead`.

### 겹치는 복구 예 (결정 96)

주문1 구매 근거 P1의 보유권 L1이 정지된 동안 고객이 주문2를 사서 등록해 L2가 `ACTIVE`이면, 주문1 복구는 다른 `ACTIVE`가 있어 L1을 `ENDED`로 끝내고 P1을 `STORED`로 돌린다(`returned_to_storage=true`, 결정 98 — 환불 상세에 표시). 이 뒤 주문1의 늦은 전액 취소가 반영되면 E7 늦은 취소 가드로 P1이 `REFUNDED`가 되고 L2는 그대로다(R11-3).

## API 계약

| 메서드 | 경로 | 역할 | 요청 | 응답 | 오류 | 멱등 |
|---|---|---|---|---|---|---|
| GET | `/api/v1/library?page=0&size=20` | MEMBER | `page ≥ 0`, `1 ≤ size ≤ 100` | `ApiResponse<LicensePage>`: `content[LicenseResponse]`·`page`·`size`·`totalElements`·`totalPages`. 정렬 `registeredAt DESC, id DESC` | 400 `INVALID_REQUEST`, 401, 403 | 조회 |
| GET | `/api/v1/library/{productCode}` | MEMBER | `productCode` `[A-Za-z0-9][A-Za-z0-9._-]{0,49}` | `ApiResponse<LicenseResponse>` — `ACTIVE` 우선, 없으면 id가 가장 큰 `SUSPENDED` | 400, 401, 403, 404 `NOT_FOUND` | 조회 |
| GET | `/api/v1/storage?page=0&size=20` | MEMBER | 위와 같음 | `ApiResponse<StoragePage>`: `content[StorageItemResponse]`. 정렬 `acquiredAt DESC, id DESC` | 400, 401, 403 | 조회 |
| POST | `/api/v1/storage/{licenseId}/registration` | MEMBER | `licenseId` UUID, 본문 없음 | `ApiResponse<LicenseResponse>`(새 보유권 또는 이미 있는 `ACTIVE`) | 400, 401, 403, 404, 409 `PRODUCT_ALREADY_OWNED`, 409 `PURCHASE_NOT_REGISTRABLE` | 자연 키 `licenseId`: 이미 등록된 같은 구매 근거는 같은 보유권을 돌려준다 |
| GET | `/api/v1/ops/dlt`, `/api/v1/ops/outbox/dead` | ADMIN | 공통 운영 API | 공통 | 400, 401, 403 | 조회 |
| POST | `/api/v1/ops/dlt/replay`, `/api/v1/ops/outbox/dead/{eventId}/requeue` | ADMIN | 공통(목표: 실패 보관 표 키 단위 재투입, 결정 160) | 공통 | 400, 401, 403, 404 | 같은 행 상태로만 |
| POST | `/api/v1/ops/outbox/{eventId}/republish` | ADMIN | 사유 1~500자 | 공통 | 400(허용 목록 밖·사유 없음), 404(없음·`SENT` 아님) | 행 잠금 아래 `SENT → PENDING` 한 번 |
| — | `/actuator/health`, `/v3/api-docs/**`, `GET /actuator/prometheus` | 공개 | — | — | — | — |

- `LicenseResponse` = `licenseId`(구매 근거 ID)·`productId`·`productCode`·`title`·`acquiredAt`(= 보유권 `registeredAt`)·`status`(`ACTIVE`/`SUSPENDED`).
- `StorageItemResponse` = `licenseId`·`orderNo`·`productId`·`productCode`·`title`·`purchasedAt`(= 구매 근거 `acquiredAt`)·`status`(`STORED` 또는 정지 값)·`registrable`.
- 조회 주체는 `@CurrentSubject`의 JWT `sub`이다. 그 밖의 경로는 `denyAll`이다. 운영 API는 `stove.messaging.ops.enabled=false`가 기본이고 Gateway가 노출하지 않는다.
- `licenseId`는 공개 조회용 UUID이며 CD키·다운로드 토큰 같은 비밀이 아니다.

## 이벤트

공통: 봉투·Outbox·Inbox·키·재시도·실패 보관·재발행은 [플랫폼 P2](../platform/requirements.md)를 따른다. 이 절은 License가 그 계약을 지키는 방법만 적는다. 행 번호는 [플랫폼 design "이벤트 목록"](../platform/design.md#이벤트-목록).

### 소비

| 이벤트(플랫폼 행) | 리스너 → Mapper 검증 → 서비스 | 소비 그룹 | 업무 멱등 키(Inbox 밖) | 실패 처리 |
|---|---|---|---|---|
| `OrderPaid` (#17, P3.15-1) | `OrderEventListener` → `LicenseOrderMapper.from`(봉투·본문 `eventId`·`eventType`, 키 = `orderNo`) → `PaidOrder` 생성자 검증(R8-2) → `GrantLicense.execute` | `license-paid-order` | `uk_product_license_order`·`_event`·`_request`·`_result`·`_payment`, 같은 주문은 13개 값 비교(`License.verify`) | 형식·불일치·출처 ID 재사용·Inbox 불일치 = 영구 |
| `LicenseSuspensionRequested` (#18, P3.18) | `LicenseSuspensionEventListener` → `LicenseSuspensionMapper.from` → `SuspensionRequest` → `ApplyLicenseSuspension.execute` | `license-suspension` | `uk_license_suspension_request`, 정지 기록 본문 비교(`LicenseSuspension.verify`) | 구매 근거 없음 = 재시도(`NOT_FOUND`), 그 밖 = 영구 |
| `LicenseRestorationRequested` (#39, P3.24-1) | `LicenseRestorationEventListener` → `RestorationRequestMapper.from` → `RestorationRequest` → `ApplyLicenseRestoration.execute` | `license-restoration` | `uk_license_restoration_request`·`_suspension`, 요청 지문 비교 | 같음 |
| `LicenseApprovalCorrectionRequested` (#40, P3.27-1) | `ApprovalCorrectionEventListener` → `ApprovalCorrectionMapper.from` → `ApprovalCorrectionRequest` → `ApplyApprovalCorrection.execute` | `license-approval-correction` | PK 요청 `eventId`, `uk_license_correction_stop`·`_suspension`, 지문 비교 | 같음 |
| `PurchaseRefunded` (#21, P3.25·P3.23-8) | `PurchaseRefundEventListener` → `PurchaseRefundMapper.from` → `PurchaseRefund` → `ApplyPurchaseRefund.execute` | `license-purchase-refund` | `REFUNDED` 끝 상태 + `refunded_suspension_request_event_id`, 목표 `uk_product_license_refund_event` | 같음 |

- 다섯 리스너 모두 `stove.order.v1` 하나만 구독한다. 처리하지 않는 `eventType`은 부수효과 없이 건너뛰고 (그룹, 타입) 지표로 센다(P2.3-7, 플랫폼 T31).
- 매퍼는 (`eventType`, `schemaVersion`)으로 고른다(R8-6, 결정 170).
- 영구 실패는 공통 영구 실패 예외로 표시해 재시도 없이 실패 보관 표로 보낸다(P2.6-3, 플랫폼 T20). 지금 License는 `BusinessException(CONFLICT|INVALID_REQUEST|NOT_FOUND)`를 던지고 공통 핸들러가 모두 4회 재시도한다(LI-T6).
- 받지 않는 이벤트: `RefundExecutionFailed`·`RefundFailureClosed`·`RefundLateCancellationConfirmed`(P3.22-3·P3.23-4, 플랫폼 "목표 행"). 보유권 복구는 `LicenseRestorationRequested`로만, 늦은 취소 회수는 `PurchaseRefunded`로만 한다.

### 발행

모두 `OutboxRecorder.record("License", licenseId, event)`로 상태 변경과 같은 트랜잭션에 쓴다. 토픽 `stove.license.v1`, 키 `orderNo`.

| 이벤트(플랫폼 행) | 발행 지점 | 본문 `occurredAt` | 받는 쪽 |
|---|---|---|---|
| `PurchaseRecordStored` (#33, 지금 `LicenseGranted`, 결정 175) | `GrantLicense.execute`(새 행일 때만) | 구매 근거 `acquiredAt` | Order `order-license-grant` |
| `LicenseSuspended` (#34) | `ApplyLicenseSuspension.execute`(첫 정지) | 정지 기록 `suspended_at` | Order `order-refund-suspension` |
| `LicenseRestored` (#42) | `ApplyLicenseRestoration.execute`(첫 복구), `returnedToStorage` 포함 | 복구 기록 `restored_at` | Order `order-refund-restoration` |
| `LicenseApprovalCorrected` (#41) | `ApplyApprovalCorrection.execute`(첫 정정), `returnedToStorage` 포함 | 정정 기록 `restored_at` | Order `order-approval-correction` |
| `LicenseRefundApplied` (가칭, 플랫폼 "목표 행", 결정 157·158) | `ApplyPurchaseRefund.execute`(`REFUNDED`로 바뀐 때만, 늦은 취소 회수 포함) | 구매 근거 `refunded_at` | Order (신설) `order-license-refund` |

`LicenseRefundApplied` 본문(제안 — 이름·칸은 플랫폼 이벤트 표에서 확정, 1회차 지적 A5·B4, 판정 전): `eventId`·`occurredAt`·`refundEventId`(회수한 `PurchaseRefunded.eventId`)·`refundId`·`licenseId`·`orderNo`·`buyerSubject`·`productId`·`productCode`·`paymentId`·`suspensionRequestEventId`. 사실 한 문장: "License가 전액 환불된 주문의 구매 근거를 `REFUNDED`로 끝내고 열린 보유권을 `ENDED`로 바꿨다". 정상 회수와 늦은 취소 회수는 같은 사실이다(원칙 ①).

이름 이행(결정 175, P2.7-4): ① Order가 `LicenseGranted`·`PurchaseRecordStored`를 모두 받는 판 → ② License가 `PurchaseRecordStored`를 내는 판 → ③ 옛 타입이 모든 Outbox·실패 보관 표에서 사라진 뒤 Order의 옛 처리 제거. 이행 동안 License 재발행 허용 목록에는 두 이름이 다 있다(옛 행 재발행용). 본문·키·소비 그룹은 같다.

### 결과 이벤트를 다시 내지 않는 규칙과 재발행

이미 처리한 요청이 다시 오면 Inbox·처리 기록 대조로 무변화이고 결과를 다시 내지 않는다(R5-6). 결과가 Kafka에서 사라졌으면 ADMIN이 License의 `SENT` 결과 행을 같은 `eventId`·본문·키로 재발행한다(R7-10·11, 결정 129·161). 허용 목록(`OutboxRepublishTargets`, `stove.messaging.ops.republish-owner=license`)은 플랫폼 이벤트 표에서 소유 = License, 상태 = 현행인 행으로 만든다: `PurchaseRecordStored`(이행 중 `LicenseGranted` 포함)·`LicenseSuspended`·`LicenseRestored`·`LicenseApprovalCorrected`·`LicenseRefundApplied`.

## 모듈 안 불변식

| ID | 불변식 | 지키는 곳 | 확인 테스트 |
|---|---|---|---|
| LI-I1 | 주문당 구매 근거 1행이고 출처 ID(`eventId`·`requestEventId`·`paymentResultEventId`·`paymentId`)는 다른 주문에 쓰이지 않는다 | `uk_product_license_order`·`_event`·`_request`·`_result`·`_payment`(`license/db/V5:60-64`), `GrantLicense.execute`(`findSourceConflicts`, `license:core/service/GrantLicense.java:37-39`) | `LicenseIntegrationTest#sourceIdentifiersCannotBeReusedForAnotherOrder`, `#simultaneousSameEventCommitsOneEvidenceAndReplaysSafely` |
| LI-I2 | 구매 근거의 13개 값·`licenseId`·`acquiredAt`·`receivedAt`은 바뀌지 않는다 | 엔티티에 변경 메서드 없음, `License.verify`(`License.java:178-180`), `GrantLicense.verifyRedelivery` | `LicenseIntegrationTest#duplicateDeliveryPreservesPublicIdTimestampsAndAllEvidence`, `#sameOrderMustMatchTheEntireOriginalFact` |
| LI-I3 | 고객·상품당 `ACTIVE` 보유권은 최대 1개 | `uk_license_registration_active`, `ProductPurchaseLock`, `License.register`(`License.java:72-77`) | `PurchaseStorageIntegrationTest#concurrentRegistrationsLeaveExactlyOneActiveLicense` |
| LI-I4 | 구매 근거당 열린 보유권은 최대 1개 | `uk_license_registration_open` | 없음 → tasks LI-T9 |
| LI-I5 | 구매 근거 상태와 열린 보유권이 맞다: `REGISTERED` ⇔ 열린 보유권 `ACTIVE` 1개, `정지·R` ⇔ 열린 보유권 `SUSPENDED` 1개, `STORED`·`정지·S`·`REFUNDED` ⇔ 열린 보유권 0개 | `License.register`·`suspend`·`restore`·`refund`·`requireCurrent`(`License.java:72-143`). DB는 두 표에 걸쳐 지키지 못한다 | 부분: `PurchaseStorageTest` 전체. 상태 조합 점검 쿼리 테스트 없음 → tasks LI-T9 |
| LI-I6 | `ENDED` ⇔ `ended_at` 있음 | `chk_license_registration_end` | 없음(제약 존재 확인) → tasks LI-T9 |
| LI-I7 | 정지 1건은 복구 1건 또는 정정 1건 중 하나로만 끝난다 | `uk_license_restoration_suspension`·`uk_license_correction_suspension`, `License.restore`(현재 정지 = 요청 정지, `License.java:100`) | `PurchaseStorageIntegrationTest#refundCompletionEndsThePurchaseAndLateRestorationCannotTouchTheNewLicense`. 복구 뒤 같은 정지의 정정 거절 테스트 없음 → tasks LI-T9 |
| LI-I8 | `REFUNDED`는 끝 상태다 | `License.register`·`suspend`·`restore`·`refund` 가드 | `PurchaseStorageTest#refundOfAnotherSuspensionIsRejected`, `PurchaseInterleavingIntegrationTest#restorationReadBeforeCommittedRefundIsRefused` |
| LI-I9 | 상태 값은 enum 허용 값뿐이고, 상태에 딸린 식별자는 그 상태일 때만 있다(R12) | `chk_product_license_status`·`chk_license_registration_status`. 정지 전 상태·식별자 조합 CHECK 없음 | 없음 → tasks LI-T2·LI-T3 |
| LI-I10 | 업무 변경과 Inbox는 함께 있거나 함께 없다(Inbox만·기록만 = 불일치) | `GrantLicense.verifyRedelivery`, 각 서비스의 `firstDelivery` 대조, `ApplyPurchaseRefund`(`changed != first` → `CONFLICT`, `ApplyPurchaseRefund.java:37-38`) | `LicenseIntegrationTest#orphanInboxAndMissingInboxCannotHideInconsistency`, `#inboxWithoutLicenseCannotBeAcceptedAsADuplicate` |
| LI-I11 | 결과 이벤트는 처리 1건당 1건이다 | `uk_license_suspension_result`·`uk_license_restoration_result`·`uk_license_correction_completed`, 재전달 분기에서 Outbox를 쓰지 않음 | `ApplyLicenseSuspensionTest#anIdenticalRequestDoesNotChangeTheStateOrPublishAnotherCompletion`, `LicenseOutboxRepublishIntegrationTest#alreadyProcessedSuspensionRestorationAndCorrectionRequestsHaveNoEffectWhenRepublished` |
| LI-I12 | 같은 처리 트랜잭션의 License 시각은 한 번 읽는다(`ended_at`·`restored_at`·결과 `occurredAt`이 같다) | 없음(지금 두 번 읽음) | 없음 → tasks LI-T5 |

모듈을 넘는 불변식: [I3](../platform/design.md#불변식-표-i1--i20)(구매 근거는 `OrderPaid`로만, `ACTIVE` ⇒ `REGISTERED`·PAID 주문), I4(`REFUNDED` ⇒ Order 환불 `COMPLETED` 또는 늦은 취소 완료), I8(1회 반영), I9·I13(다른 모듈 시각 비교 없음), I14(정방향 — Order가 `PurchaseRecordStored`·`LicenseRefundApplied` 미수신을 셈), I17(복구는 실패 확정 뒤, 정지 1건당 1건).

## 외부 연동과 adapter 메모

- 외부 공급자 없음, `core.port` 없음, `adapter` 패키지 없음. 결제 공급자 규칙은 License에 오지 않는다(원칙 ②).
- Download는 고객 토큰으로 `GET /api/v1/library/{productCode}`를 불러 `status=ACTIVE`만 권한으로 본다(소비 측 계약, [P3.8-1](../platform/requirements.md)). License는 Download에 사본을 밀어 넣지 않는다.
- Order는 License 결과 이벤트로 지급 확인·정지 확인·복구 확인·회수 확인(목표)을 한다.

## 설정

| 키·빈 | 값 | 운영/테스트 차이 |
|---|---|---|
| `server.port` | 8084 | 같음 |
| `spring.kafka.consumer.auto-offset-reset` | `earliest`(결정 99: 배포 순서가 달라도 채움 이벤트를 받음) | 같음 |
| `spring.kafka.listener.ack-mode` | `record`, `enable-auto-commit=false` | 같음 |
| `stove.outbox.relay-enabled`·`batch-size`·`poll-interval-ms`·`max-retry` | `true`·200·200·10 | 같음 |
| `stove.messaging.ops.enabled` | `false`(기본 꺼짐) | 같음. 켜는 것은 운영 절차 |
| `stove.messaging.ops.republish-enabled`·`republish-owner` | `true`·`license` | 같음(ops가 켜진 인스턴스에서만 열림) |
| `Clock` 빈 | 목표: 서비스·도메인이 쓰는 License 시계는 마이크로초 `Clock` 빈 하나(지금 `licenseSuspensionClock` = `Clock.tick(systemUTC, 1µs)`, `license:config/LicenseSuspensionConfiguration.java:30-33`) | 운영 계약은 같고 테스트만 고정 `Clock`을 주입한다. 지금 `License.grant`·`RegisterLicense`는 빈을 쓰지 않는다(LI-T5) |
| 장애 주입 `LicenseFaultConfiguration` | `integrationTest` 클래스패스에만 | 운영 bootJar에 없음(원칙 ⑦) |

License 업무 규칙에는 설정 키가 없다(기한·금액·공급자 값을 갖지 않는다).
