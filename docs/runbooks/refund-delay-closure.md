# 런북 — 장기 미확정·귀속 미확정 환불을 닫을 때

[결정 127](../decisions.md#refund-delay-closure)의 운영 절차다. 계약은 [Payment R14](../../.kiro/specs/payment/requirements.md)와 [설계](../../.kiro/specs/payment/design.md)를 정본으로 한다.

시스템은 PG 취소를 실행하지 않는다. 돈은 운영자가 PortOne 콘솔에서 움직이고, 시스템은 PG를 다시 조회해 **같은 원결제의 전액 취소**가 확인될 때만 닫는다.

## 먼저 판단할 것 — 이 런북이 맞는 상황인가

| 경보·상황 | 무엇인가 | 이 런북 |
|---|---|---|
| `RefundConfirmationsDelayed`(critical) | 최초 취소 준비 + 3시간이 지났는데 PG 결과가 미확정(`GET /api/v1/admin/refund-delays`) | **맞다.** 고객에게 환불할 건이면 아래 1번부터 |
| `RefundsUnattributed`(warning) | PG 조회로 전액 취소는 확인돼 고객 환불은 끝났지만, 우리 시도의 취소인지 모름(`?kind=UNATTRIBUTED`) | **맞다.** 아래 3번부터(콘솔 취소 불필요) |
| PG가 취소를 거절했고 지금 환불할 수 없는 건 | 돈이 움직이지 않은 실패 | **아니다.** [결정 128](../decisions.md#refund-failure-closure) 실패 종결(미구현) |
| 3시간 창 안의 `POST 403`·`400 INVALID_REQUEST` 중단 | 운영 중단 | **아니다.** `/api/v1/admin/refund-cancellation-holds` 재개 |

**환불해 줄 건이면 실패 종결보다 콘솔 취소 → 결정 127 종결을 먼저 한다**([결정 134](../decisions.md#refund-failure-customer-notice)). 실패 종결은 지금 환불할 수 없는 건에만 쓴다. 실패로 닫으면 보유권이 복구되고, 새 환불은 고객이 다시 신청해야만 생기기 때문이다.

## 순서 — 콘솔 취소 → 종결

1. **대상 확인.** `GET /api/v1/admin/refund-delays`(또는 `?kind=UNATTRIBUTED`)에서 `attemptId`·`paymentId`·`orderNo`·금액을 확인한다. 상세 `GET /api/v1/admin/refund-delays/{attemptId}`의 `receiptState`(우리 취소 접수 여부)·`nextLookupAt`·`closure`(이미 닫혔는지)를 본다. 확인 내용은 `POST …/{attemptId}/investigations`로 남길 수 있다(미확정 건만).
2. **PortOne 콘솔에서 취소.** 미확정 건만 해당한다. 콘솔에서 그 `paymentId`의 결제 상태를 보고, 아직 `PAID`이고 잔액이 있으면 **전액**을 취소한다. 부분 취소는 하지 않는다. 이미 전액 취소돼 있으면 콘솔에서 다시 취소하지 않는다.
3. **종결 요청.** `POST /api/v1/admin/refund-delays/{attemptId}/closure`에 `{requestId(UUID), attribution, note}`를 보낸다.
   - `attribution`: `OUR_REQUEST`(우리 취소 요청이 일으킨 취소) / `EXTERNAL_CANCELLATION`(콘솔 등 외부 취소, 2번에서 직접 취소했으면 이것) / `UNDETERMINED`(판단 불가). 기록만 하며 금액·상태를 바꾸지 않는다.
   - `note`: 콘솔에서 본 내용과 판정 근거(500자 이내).
   - 응답이 끊기면 **같은 `requestId`·같은 내용으로 다시 보낸다.** 최초 결과가 돌아오고 PG를 다시 조회하지 않는다. 내용을 바꾸면 409 `REFUND_CLOSURE_CHANGED`다.
4. **결과 확인.** 200이면 `closure.outcome`을 본다.
   - `COMPLETED`: 미확정 건을 이 종결이 기존 조회 완료 경로로 완료했다. `RefundPaymentConfirmed`가 나가 Order가 고객 환불을 `COMPLETED`로 바꾼다.
   - `RECORDED`: 이미 완료된 귀속 미확정 건에 판정만 남겼다. 새 이벤트는 없다.
   - 두 경우 모두 지연·귀속 미확정 목록과 게이지에서 빠지고, 경보는 다음 평가에서 풀린다.

## 409가 오면

| 코드 | 뜻 | 할 일 |
|---|---|---|
| `REFUND_CLOSURE_PG_NOT_CANCELLED` | PG가 아직 결제 상태(잔액 있음)·부분 취소·취소 진행 중이다 | 콘솔에서 전액 취소 뒤 3번을 다시 |
| `REFUND_CLOSURE_BUSY` | 자동 PG 조회가 진행 중이다 | 잠시 뒤 같은 요청을 다시 |
| `CLOSURE_PG_UNAVAILABLE` / `CLOSURE_PG_INVALID_EVIDENCE` | PG 조회 장애 / 응답이 원결제와 맞지 않음 | 장애는 회복 뒤 재시도. 근거 불일치는 콘솔 결제·상점·채널을 조사 |
| `REFUND_NOT_CLOSABLE` | 3시간 창 안의 미확정, 이미 성공·실패로 끝남, 이미 종결됨 | 상세의 `executionStatus`·`closure` 확인. 창 안이면 자동 조회를 기다림 |
| `CONFLICT` | 귀속 미확정 건의 PG 취소 내역이 처음 확인한 내역과 다르다 | 종결하지 말고 콘솔 내역을 조사 |

## 하지 않는 것

- SQL로 `refund_execution`·`refund_closure`를 고치지 않는다. 종결 기록은 append-only이고 실행 삭제를 막는다(V39).
- 시간만으로 실패·보유권 복구·새 취소 요청을 만들지 않는다([결정 29](../decisions.md#full-refund-policy) 하위).
- Order 시도의 조사 표시(`reconciliationRequired`)는 이 종결로 지워지지 않는다. Payment 상세의 `reconciliationRequired`는 종결 뒤 `false`다. Order 쪽 해제는 남은 결정이다(Payment `tasks.md`).
- 현재 구성에는 Alertmanager가 없어 경보를 silence로 끌 수 없다. `RefundConfirmationsDelayed`·`RefundsUnattributed`는 위 종결로 대상이 없어지면 다음 평가에서 풀린다.
