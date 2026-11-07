# 작업: Payment

근거 수치·원문은 [근거 색인](../../../docs/evidence.md)을 따른다.

## 완료

- [x] `OrderPlaced` 내구 접수(요청 전체 비교·Inbox·접수 경합), `PENDING` 결제 생성 — 2026-10-16
- [x] 미확정 건 주기 재개(기본 1초·20건·오류 재시도 5초), 재시작 후 DB `PENDING` 재개 — 2026-10-16
- [x] 세 트랜잭션 경계(접수·트랜잭션 밖 PG 호출·결과 행잠금), 고정 paymentId 멱등 키 — 2026-10-16
- [x] 확인된 승인·거절만 `PAID`/`FAILED` 확정, 결과·Outbox 동시 커밋, 응답 유실·결과 저장 실패는 `PENDING` 유지 — 2026-10-16
- [x] `OrderPaymentCompleted`/`OrderPaymentFailed` 발행, DEAD 보존·원래 ID/본문 재발행 — 2026-10-16
- [x] 내 결제 목록·상세 GET, JWT sub 소유권·타인 404·페이지 정렬, 구 prepare/callback/cancel/simulator·Gateway 예외 제거 — 2026-10-16
- [x] 8개 앱 실제 연계 11단계(승인·거절·중단·재시작·결과 전달 복구) — 2026-10-16 ([원문](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/payment-mvp-plan.md#재작성-완료-기록))
- [x] 외부 모의 PG 분리(simulators/mock-pg 전용 프로세스·DB·HTTP), `SimulatedPgHttpGateway`, V12 비용 스냅샷(미산정 null) — 2026-10-18
- [x] PortOne V2·토스페이(`tosspay_v2`) TEST 채널 독립 결제·전액 취소 검증 — 2026-10-27 ([원문](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/pg-test-integration-plan.md))
- [x] 웹훅 접수(서명·스토어·재전달 검증, 조회 힌트), checkout 전용 API(A안), 공개 HTTPS edge·실제 Ready/Paid 웹훅 수신 — 2026-10-27
- [x] 같은 주문 재결제 A안: 개별 FAILED 별도 이력, `PENDING` checkout 재사용, 실패 시도 이력 GET, 승인/Outbox 선행 커밋 — 2026-10-29 (결정 31)
- [x] 서버 승인 연결: 암호화 접수(AES-GCM)·3시간 복구 창·검증된 실패 재진입·자동 만료 스캔 연동, `PAID`만 확정 — 2026-10-29 ([결정 32](../../../docs/decisions.md#order-expiry-approval-admission)·[결정 33](../../../docs/decisions.md#payment-approval-recovery), [원문](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/payment-confirmation-plan.md))
- [x] Order 만료 결과 수신(`OrderExpired`·원구매 대조·주문별 잠금·늦은 `OrderPlaced`·조회 예약 종료) — 2026-10-29 ([원문](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/order-expiry-propagation-plan.md))
- [x] 실제 TEST 생명주기: 서버 승인→PAID, 시도 실패 뒤 같은 주문 재결제, 승인 응답 유실 복구, 30분 자연 만료·새 주문, PG 할인 A안·V32 — 2026-10-29 ([결정 34](../../../docs/decisions.md#provider-discount-payment-amounts), [원문](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/g1-portone-live-verification.md))
- [x] 승인 전 검증 보류(`REVIEW_REQUIRED`·`PG_INVALID_EVIDENCE`)의 만료 반영 경로 보완 — 2026-10-30 ([원문](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/g1-unadmitted-expiry-fix.md))
- [x] 환불 실행·확인: 고정 attemptId·3시간 창·원액 잔액 조건, 전액 취소 조회 확인(A)·검증된 취소 완료 응답(A), `RefundPaymentConfirmed`, 원 PAID 불변 — ([원문](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/pg-test-integration-plan.md)·[인계](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/refund-session-handoff.md))
- [x] 환불 운영: ADMIN `/api/v1/admin/refund-cancellation-holds` 중단/재개, `/api/v1/admin/refund-delays` 장기 미확정 조사 — 2026-10-29
- [x] 실제 자동 환불 전체 흐름 1건, 실제 ADMIN 수동 심사 PG 환불(2차 승인→License SUSPENDED→PortOne CANCELLED) 1건 — 2026-10-30
- [x] G5 웹훅 수신 경계: 누락/중복 헤더·시간/원문/크기·접수 보존·비활성 제공자·키 조립 거절 — 2026-11-05 ([원문](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/g5-webhook-verification.md); 공개 HTTPS·실제 제공사·배포 검증은 별도)

## 진행 중 / 남음

- [x] [결정 112](../../../docs/decisions.md#approval-review-hold-closure) 1부: 승인 보류 ADMIN 조회·최신 PG 관측·조사 기록·사유별 게이지/경보 — 2026-11-07 (운영 종결·간격·고객 안내는 G1 미정)
- [ ] G1 종료 조건 대조: 금융 보류(`REVIEW_REQUIRED` 등) 건의 다음 행동·운영 종결 정책. 승인 전 검증 보류의 만료 경로 보완은 모든 `REVIEW_REQUIRED`의 해제·재승인·금융 구제 정책이 아니며, 과거 실제 TEST 진단 건의 원래 기록·종료된 스택은 수정하지 않았다 ([계획](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/platform-generalization-plan.md#g1에서-바로-시작할-구체적-작업))
- [ ] 결제창 이탈 후 재진입의 Order 결과/다음 행동 검증: 실제 TEST로는 시도 실패(결제창 닫기)→같은 주문 재결제와 이탈→30분 자연 만료를 확인했다. 나머지 이탈/재진입 조합의 범위는 G1 종료 조건 대조에서 정한다 — G1
- [ ] 같은 공급자 거래/비용·지급 자료로 Settlement까지 연결, 미확정 비용 모델 확정 — G3 ([계획](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/platform-generalization-plan.md))
- [ ] 실제 PG 오류(POST 403·정확한 400 등) 뒤 운영 재개를 실제 ADMIN 경로로 검증 — G2 후속
- [ ] 나머지 HTTP 오류(5xx·일반 409·기타 400)의 업무 분류와 `INVALID_REQUEST` 본문 정정 계약 — G2 후속
- [ ] 장기 미확정 환불의 알림/경보·조사 종결/담당 배정, 귀속 미확정 완료 건(`PG_QUERY_UNATTRIBUTED`)의 조사 종결 절차 — G2 후속
- [ ] 처리량 증가 시 첫 환불 송신을 별도 DB 송신 작업자로 옮기는 B안(전환 판단 지표: 접수 지연·PG p95/p99·소비 lag·최장 대기) — 후속
- [ ] 새 환불 이벤트·재개 작업의 Tempo 추적 전체 연결 검증 — 후속
- [ ] 실제 PortOne·PG로 승인·취소 다시 확인: 토스페이 공용 예제 키가 2026-11-07 거절돼 우리 전용 테스트 가맹점이 필요. 방법은 가맹점 준비 뒤 정함 — [결정 110·111](../../../docs/decisions.md#portone-fake-journey). 반복 검증은 PortOne 흉내 서버 여정(`verify-refund-settlement.py`)이 맡음
- [ ] 실제 TEST에서 실패 시도 거래 이력 필드 충족 여부 확인 — G1 재결제 여정과 함께

## 범위 밖(후속)

- LIVE 결제·실제 PG 지급 자료·은행 입금·판매자 송금(별도 계약·설정·운영 검증).
- 다중 PG·다중 워커 최적화·처리량 향상·분산 임대/ShedLock.
- 카드 정보 저장·결제 수단 다양화·쿠폰/다중 통화/무료 상품 지급·복수 상품/수량.
- 전용 고객 결제 화면(현재 백엔드 범위 밖).
