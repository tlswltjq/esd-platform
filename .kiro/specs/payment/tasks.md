# 작업: Payment

## 완료 (MVP·검증 기록 있음)

- [x] `OrderPlaced` 내구 접수(요청 전체 비교·Inbox·접수 경합), `PENDING` 결제 생성 — 2026-10-16 `docs/payment-mvp-plan.md`
- [x] 미확정 건 주기 재개(기본 1초·20건·오류 재시도 5초), 재시작 후 DB `PENDING` 재개 — 2026-10-16
- [x] 세 트랜잭션 경계(접수·트랜잭션 밖 PG 호출·결과 행잠금), 고정 paymentId 멱등 키 — 2026-10-16
- [x] 확인된 승인·거절만 `PAID`/`FAILED` 확정, 결과·Outbox 동시 커밋, 응답 유실·결과 저장 실패는 `PENDING` 유지 — 2026-10-16
- [x] `OrderPaymentCompleted`/`OrderPaymentFailed` 발행, DEAD 보존·원래 ID/본문 재발행 — 2026-10-16
- [x] 내 결제 목록·상세 GET, JWT sub 소유권·타인 404·페이지 정렬 — 2026-10-16
- [x] 구 prepare/callback/cancel/simulator·Gateway 익명/CSRF 예외 제거 — 2026-10-16
- [x] 8개 앱 실제 연계 11단계(승인·거절·중단·재시작·결과 전달 복구), 합계 843건 — 2026-10-16 `docs/payment-mvp-plan.md#재작성-완료-기록`
- [x] 외부 모의 PG 분리(simulators/mock-pg 전용 프로세스·DB·HTTP), `SimulatedPgHttpGateway`, V12 비용 스냅샷(미산정 null) — 2026-10-18
- [x] PortOne V2·토스페이(`tosspay_v2`) TEST 채널 독립 결제·전액 취소 검증 — 2026-10-27 `docs/pg-test-integration-plan.md`
- [x] 웹훅 접수(서명·스토어·재전달 검증, 조회 힌트), checkout 전용 API(A안), 공개 HTTPS edge·실제 Ready/Paid 웹훅 수신 — 2026-10-27
- [x] 같은 주문 재결제 A안: 개별 FAILED 별도 이력, `PENDING` checkout 재사용, 실패 시도 이력 GET, 승인/Outbox 선행 커밋, 합계 640건 — 2026-10-29 `docs/decisions.md#31`
- [x] 서버 승인 연결: 암호화 접수(AES-GCM)·3시간 복구 창·검증된 실패 재진입·자동 만료 스캔 연동, `PAID`만 확정, 합계 1,219건 — 2026-10-29 `docs/payment-confirmation-plan.md`, `docs/decisions.md#32`·`#33`
- [x] Order 만료 결과 전달(OrderExpired·원구매 대조·주문별 잠금·늦은 OrderPlaced·조회 예약 종료), 합계 1,346건 — 2026-10-29 `docs/order-expiry-propagation-plan.md`
- [x] 실제 TEST 생명주기 1건: 실제 서버 승인→PAID, FAILED 뒤 같은 주문 재결제, 승인 응답 유실 복구, 30분 자연 만료·새 주문, PG 할인 A안·V32, Payment check 597건 — 2026-10-29 `docs/g1-portone-live-verification.md`
- [x] 환불 실행·확인: 고정 attemptId·3시간 창·원액 잔액 조건, 전액 취소 조회 확인(A)·검증된 취소 완료 응답(A), `RefundPaymentConfirmed`, 원 PAID 불변 — `docs/pg-test-integration-plan.md`·`docs/refund-session-handoff.md`
- [x] 환불 운영: ADMIN `/api/v1/admin/refund-cancellation-holds` 중단/재개, `/api/v1/admin/refund-delays` 장기 미확정 조사 — `docs/refund-session-handoff.md`
- [x] 실제 자동 환불 전체 흐름 1건, 실제 ADMIN 수동 심사 PG 환불(2차 승인→License SUSPENDED→PortOne CANCELLED) 1건 — 2026-10-30 `docs/refund-session-handoff.md`

## 진행 중 / 남음 (일반화 공백)

- [ ] 실제 TEST 재결제 전체 여정 검증 — G1 (`docs/platform-generalization-plan.md#g1에서-바로-시작할-구체적-작업`)
- [ ] 결제창 이탈/재진입·안전한 종료 경계(브라우저 닫기·시각 경과만으로 실패 확정하지 않음), 성공·시도 실패·중단 후 재진입·응답 유실을 Order 결과/다음 행동까지 검증 — G1
- [ ] 초기 진단 금융 보류(REVIEW_REQUIRED 등) 건의 다음 행동과 G1 종료 조건 대조 — G1
- [ ] 같은 공급자 거래/비용·지급 자료로 Settlement까지 연결, 미확정 비용 모델 확정 — G3 (platform-generalization-plan 5절 Payment 행·G3)

## 범위 밖(후속)

- LIVE 결제·실제 PG 지급 자료·은행 입금·판매자 송금(별도 계약·설정·운영 검증).
- 다중 PG·다중 워커 최적화·처리량 향상·분산 임대/ShedLock.
- 카드 정보 저장·결제 수단 다양화·쿠폰/다중 통화/무료 상품 지급·복수 상품/수량.
