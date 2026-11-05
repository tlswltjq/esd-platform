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

## 진행 중 / 남음 (G3 공백)

- [ ] PG 비용 LIVE 확정: 실제 가맹점 키·거래 키 대응(`PortOne pgTxId ↔ 토스페이 payToken`, `paymentId ↔ PG orderNo`) 검증과 `identity-mapping-verified` 활성화 — G3
- [ ] 정상 LIVE 승인 근거 수용의 도메인·스키마·조회 계약 — G3 (현재 운영 계약은 PORTONE_TEST)
- [ ] 정상 LIVE 입력 → 비용 저장/SQL 집계, 동시 첫 비용 INSERT, 해당 INSERT 실패 롤백의 제약 보존 상태 검증 — G3 (현재 검증 공백)
- [ ] 환불 조정 완료와 판매자 순액 전체 연결 — G3
- [ ] 음수 월 순액·플랫폼 잔여액의 이월·실제 지급/회수 정책 — G3 (현재 음수 표시만, 실행 없음)

## 범위 밖(후속)

- 실제 은행 입금 확인·판매자 송금·지급대행, 세금계산서·원천징수·세무 회계.
- 공통 운영 API(`/api/v1/ops/**`)·Outbox 릴레이·자동 마감 배치·마감 취소/재마감·확정 금액 수정.
- 할인·쿠폰·플랫폼 보조금·복수 상품·다중 통화·판매자별 요율/계약·차지백·수수료 반환.
- 일반 정산 API 밖의 카드 차액 정산 등 별도 비용 자료 출처.
- P4 환불 후 재구매 허용/불허 정책(후속 논의).
