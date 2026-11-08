# 작업: Settlement

## 완료 (MVP·검증 기록 있음)

- [x] 독립 외부 모의 PG(simulators/mock-pg): 전용 프로세스·DB·HTTP API·키별 권한·승인/조회·정산 명세·검증용 제어·멱등/배정 규칙 — 2026-10-18 (완료 모듈 수 미포함)
- [x] Payment 외부화: `PgGateway`의 HTTP 구현·비용 보존·V12·응답 유실/PG 중단 복구·구 원장 이관 도구 — 2026-10-18
- [x] 매출 접수(RecordPaidSale): V6·입력 mapper·PG 거래/비용·Catalog 소유자 직접 조회·원장/월 합계/Inbox 원자 트랜잭션·13개 값 비교·중복/출처 재사용 차단 — 2026-10-18
- [x] PG 수령(ReceivePgSettlement·ReconcilePgReceipt): V7·SIMULATED 명세 접수/재대사·미도착(REPORTED_PAID)/불일치(MISMATCH)·최초 수령(SIMULATED_RECEIVED)·재시작 복구 — 2026-10-18
- [x] 월 마감·조회: 월 잠금·지연 거래(마감 후 현재 월 반영)·수동 마감·본인/운영/PG 수령 조회·보안·OpenAPI·구 코드/설정 정리 — 2026-10-18
- [x] 로컬 OrbStack 검증: 집중 검사 + 완료 모듈 회귀 + 열한 업무 앱 + 외부 PG 여정 (단위 1,014·관련 통합 152·실제 여정 32단계, 실패·오류·건너뜀 0) — 2026-10-18
- [x] G3 책임 분리·PG 비용 독립 월 마감: 원매출/배분·판매자 월·PG 거래/비용/대사 분리, V8, 비용 미확정 마감 (OCI 75건) — 2026-10-31, 결정 38·39
- [x] G3 외부 부담 혜택 A: PortOne TEST 원승인 직접 읽기(`PgFunding`)·총액/실결제/혜택 보존·주문 금액 배분·V9 (OCI 84건) — 2026-10-31, 결정 40
- [x] G3 전액 환불 배분 A: `ReverseOriginalFullRefundPolicy` 원배분 음수 상계 계산 (OCI 92건) — 2026-11-02, 결정 41
- [x] G3 환불 반영 월 A: `UseConfirmedRefundMonthPolicy` 확인 월 OPEN/CLOSED 이월 (OCI 102건) — 2026-11-02, 결정 42
- [x] G3 환불 원장·월 합계 연결: `RefundPaymentConfirmed` 소비·`PgRefunds` 취소 직접 GET·불변 환불 원장·월 환불 합계·역할별 조회·V10 (OCI 120건) — 2026-11-02
- [x] G3 토스페이 정산 API 수집 A: 전체 페이지·불변 원본/거래 정본·중복/충돌·거래별 대사·ADMIN 조회·주기 재수집·V11 (OCI 144건) — 2026-11-02, 결정 43
- [x] G3 PG 비용 업무 반영 월 연결·지급일 대사: `settlement_pg_cost_posting`·월 금융 요약·`pg-payouts` 조회·원본 재대사·V12 (OCI 167건) — 2026-11-02, 결정 45
- [x] G3 테스트 경계 정비: 루프백 HTTP 서버·저장소 대역 합성 LIVE 자료 분리, 제약 해제 픽스처 제거 (OCI 176건) — 2026-11-02, 결정 46
- [x] 환불 → 정산 조정 실제 스택 여정: `PORTONE_TEST` 모드의 Settlement가 PortOne 흉내 서버로 원승인(`PgFunding`)·취소(`PgRefunds`)를 직접 조회해 매출 2건·환불 1건 반영(`verify-refund-settlement.py`) — 2026-11-07, [결정 111](../../../docs/decisions.md#portone-fake-journey), PR #129

## 진행 중 / 남음 (G3 공백)

- [ ] [외부 대기] PG 비용 LIVE 확정: 실제 가맹점 키·거래 키 대응(`PortOne pgTxId ↔ 토스페이 payToken`, `paymentId ↔ PG orderNo`) 검증과 `identity-mapping-verified` 활성화 — G3
- [ ] [결정 필요] 정상 LIVE 승인 근거 수용의 도메인·스키마·조회 계약 — G3 (현재 운영 계약은 PORTONE_TEST)
- [ ] 정상 LIVE 입력 → 비용 저장/SQL 집계, 동시 첫 비용 INSERT, 해당 INSERT 실패 롤백의 제약 보존 상태 검증 — G3 (현재 검증 공백)
- [x] 환불 조정 완료와 판매자 순액 전체 연결 — G3, [결정 121](../../../docs/decisions.md#seller-negative-net-carryover)·[결정 130](../../../docs/decisions.md#seller-close-order-carryover). 이전 월에 OPEN 행이 있으면 마감 409, 마감 트랜잭션에서 V13 불변 이월 원장(`settlement_seller_carryover`) 한 행, 월 응답 `carriedIn`·`payable`·`carriedOut`(OPEN은 null, 원장 없는 옛 CLOSED 월은 `carriedIn = 0`). 송금·청구·회수 없음 — 2026-11-08, 단위·아키텍처 143·통합 70건 통과, PR 본문
- [ ] [결정 필요] 이미 CLOSED인 월보다 이전 월에 늦은 매출·환불이 새 OPEN 행을 만드는 경우의 이월 — G3. 예: 9월에 행이 없고 10월을 마감한 뒤, 9월 30일에 확인된 환불 이벤트가 늦게 도착하면 `RecordConfirmedRefund`가 9월 OPEN 행을 만든다(`RecordPaidSale`도 같음). 9월을 마감하면 그 `carriedOut`은 이미 마감한 10월로 갈 수 없고, 11월 마감은 직전 CLOSED 월(10월)에서 받으므로 9월 음수가 사라진다. V13 이전에 순서 없이 마감한 옛 자료(뒤 월 CLOSED·앞 월 OPEN)도 같다. 결정 121·130에 없는 경우라 구현하지 않았다
- [x] 판매자·운영 매출 조회(`/me/sales`·`/sales`)에 그 매출의 환불 연결(환불 ID·반영 월)을 보인다. 원장에 이미 있는 사실을 조회에 붙이는 일이며, 서로 다른 월의 매출·환불을 원매출로 함께 조회하기 위함이다([결정 42](../../../docs/decisions.md#settlement-refund-posting-month) 검토 메모) — 2026-11-08, `SaleResponse.refund`(환불 없으면 null), 단위 137·통합 64건 통과, PR #142
- [ ] [결정 필요] 판매자 실제 송금·청구/회수 정책 — G3 이후. 판매자 음수 월 순액의 월 이월과 지급 기준액은 [결정 121](../../../docs/decisions.md#seller-negative-net-carryover)로 정했다. 플랫폼 잔여액 음수는 요구사항(음수 보존, 판매자 전가 없음)대로 둔다. 실제 송금·청구 기능을 만들 때 정한다
- [ ] PORTONE_TEST 원승인 읽기·토스페이 정산 수집 설정을 실제 스택(Compose override)에 적용하고 런타임 검증 — G3 (원승인·취소 직접 조회는 PortOne 흉내 서버 여정의 호스트 JVM 스택에서 런타임 확인 — 결정 111. Compose override 적용, 토스페이 정산 수집, 실제 공급자 연결은 남음)
- [x] G3 변경 이후 열한 업무 앱 + 모의 PG 전체 여정(`tools/verification/verify-settlement.py`) 재실행 — 2026-11-07 21단계 통과([근거 색인](../../../docs/evidence.md)). 모의 PG 기준이며 최신 PortOne 연결 검증이 아니다.
- [ ] [결정 필요] 환불 → 정산 조정의 실제 스택 여정 — 위 여정에는 환불 단계가 없다. 모의 PG에 취소 기능이 없고, Settlement의 환불 근거 조회(`PgRefunds`)는 PortOne 조회만 받는다. 실행 방식은 정책 선택이 필요하다(PortOne TEST는 사람 승인 필요).
- [ ] [외부 대기] 실제 PG 지급 수령 확인: PORTONE 공급자의 지급 명세 수집(현재 503)과 PG 지급 보고 ≠ 은행 실입금을 구분한 별도 수령 계약 — G3 이후

## 범위 밖(후속)

- 실제 은행 입금 확인·판매자 송금·지급대행, 세금계산서·원천징수·세무 회계.
- 공통 운영 API(`/api/v1/ops/**`)·Outbox 릴레이·자동 마감 배치·마감 취소/재마감·확정 금액 수정.
- 할인·쿠폰·플랫폼 보조금·복수 상품·다중 통화·판매자별 요율/계약·차지백·수수료 반환.
- 일반 정산 API 밖의 카드 차액 정산 등 별도 비용 자료 출처.
- 부분 환불·부분/진행 중 취소 원매출의 지연 접수, SIMULATED 공급자 환불 읽기 계약(현재 503).
- 기존 플랫폼 수수료(`PlatformFeePolicy` 구체 record)·자동 환불 기한(`RefundPolicy`)의 정책 인터페이스화. 현재 인터페이스는 환불 배분·환불 월 정책에만 있다.
- 공유 스택의 구 내장 PG 원장 실제 이관(도구·전용 DB 검증만 완료), 미산정 비용의 사후 확정.
