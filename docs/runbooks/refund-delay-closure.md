# 런북 — 장기 미확정·귀속 미확정 환불을 닫을 때, 자동 송신이 멈춘 환불을 실패로 닫을 때

[결정 127](../decisions.md#refund-delay-closure)(완료 종결)과 [결정 128](../decisions.md#refund-failure-closure)(실패 종결)의 운영 절차다. 계약은 [Payment R14·R15](../../.kiro/specs/payment/requirements.md)와 [설계](../../.kiro/specs/payment/design.md)를 정본으로 한다.

시스템은 PG 취소를 실행하지 않는다. 돈은 운영자가 PortOne 콘솔에서 움직이고, 시스템은 PG를 다시 조회해 **같은 원결제의 전액 취소**가 확인될 때만 완료로 닫고, **원결제 `PAID`·취소액 0·진행 중이거나 성공한 취소 없음**이 확인될 때만 실패로 닫는다.

## 먼저 판단할 것 — 이 런북이 맞는 상황인가

| 경보·상황 | 무엇인가 | 이 런북 |
|---|---|---|
| `RefundConfirmationsDelayed`(critical) | 최초 취소 준비 + 3시간이 지났는데 PG 결과가 미확정(`GET /api/v1/admin/refund-delays`) | **맞다.** 고객에게 환불할 건이면 아래 1번부터 |
| `RefundsUnattributed`(warning) | PG 조회로 전액 취소는 확인돼 고객 환불은 끝났지만, 우리 시도의 취소인지 모름(`?kind=UNATTRIBUTED`) | **맞다.** 아래 3번부터(콘솔 취소 불필요) |
| 자동 송신이 멈춘 건(403·400 중단, 잔액 변화 차단, 3시간 창 종료)이고 지금 환불할 수 없는 건 | 돈이 움직이지 않은 실패 | **맞다.** 아래 "실패 종결" |
| 3시간 창 안의 `POST 403`·`400 INVALID_REQUEST` 중단이고 원인이 풀린 건 | 운영 중단 | `/api/v1/admin/refund-cancellation-holds` 재개가 먼저다. 재개할 수 없으면(`INVALID_REQUEST`처럼 같은 본문으로는 같은 결과) 아래 "실패 종결" |
| `RefundCancelledAfterFailure`(warning) | 실패로 끝난 환불(보유권 복구) 뒤 PG에서 전액 취소가 발견됐다(`?kind=LATE_CANCELLATION`) | **맞다.** 아래 "늦은 전액 취소" |

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

## 실패 종결 — 중단 원인 확인 → 실패 종결 또는 콘솔 취소 뒤 결정 127 종결

1. **중단 원인 확인.** 상세 `GET /api/v1/admin/refund-delays/{attemptId}`의 `activeCancellationHoldReason`(403 `PG_REQUEST_FORBIDDEN`·400 `PG_REQUEST_INVALID`), `cancellationBalanceChangedAt`(잔액 변화), `delayThresholdAt`(3시간 창 끝), `receiptState`(우리 취소를 PG가 받았는지)를 본다. 3시간 창 안의 중단은 상세가 404일 수 있으니 `GET /api/v1/admin/refund-cancellation-holds`에서 찾는다. 원인(키 권한, `PORTONE_STORE_ID` 같은 설정, 코드 결함)을 먼저 고친다. 같은 시도의 저장 본문은 고치지 않는다([결정 133](../decisions.md#refund-formal-rejection-stop)).
2. **환불해 줄 건인지 정한다.** 환불해 줄 건이면 실패 종결하지 않는다. PortOne 콘솔에서 전액 취소하고 위 "콘솔 취소 → 종결"(결정 127)로 닫는다. 실패 종결은 지금 환불할 수 없는 건, 또는 원인을 고친 뒤 고객이 다시 신청하게 할 건에만 쓴다.
3. **실패 종결 요청.** `POST /api/v1/admin/refund-delays/{attemptId}/failure-closure`에 `{requestId(UUID), note}`를 보낸다. `note`에는 중단 원인과 고친 내용(500자 이내)을 쓴다. 시스템이 PG를 다시 조회해 원결제 `PAID`·취소액 0이고 목록의 취소가 모두 `FAILED`일 때만 닫는다. 응답이 끊기면 **같은 `requestId`·같은 내용으로 다시 보낸다.** 늦은 상태 변화가 있어도 최초 응답이 그대로 돌아온다.
4. **결과 확인.** 200이면 `executionStatus`가 `FAILED`이고 `failureClosure`에 근거 종류(`NO_LINKED_CANCELLATION`·`LINKED_CANCELLATION_FAILED`)·중단 원인(`CANCELLATION_HOLD`·`BALANCE_CHANGED`·`WINDOW_ENDED`)·PG 조회 스냅샷 해시·관측한 `FAILED` 취소 ID가 남는다. 기존 `RefundExecutionFailed`가 나가 Order가 보유권을 복구하고 고객 환불은 `FAILED`(재신청 안내, [결정 134](../decisions.md#refund-failure-customer-notice))가 된다. 새 환불은 고객이 다시 신청할 때만 생긴다.
5. **실패 뒤 조회.** 실패한 건도 PG를 낮은 빈도(최대 10분 간격)로 계속 조회한다. 고객이 다시 신청해 새 시도가 접수되면 그 시도가 조회를 이어받고 이 건의 조회는 멈춘다.

## 늦은 전액 취소 — 실패 뒤 PG에서 전액 취소가 발견됐을 때

`RefundCancelledAfterFailure` 경보가 울리면 `GET /api/v1/admin/refund-delays?kind=LATE_CANCELLATION`에서 `lateCancellationObservedAt`(발견 시각)·`lateCancelledAt`(PG 취소 시각)과 `failureClosure`를 확인한다. 시스템은 보유권과 환불 상태를 바꾸지 않았고 이벤트도 보내지 않았다. 고객은 돈과 게임을 함께 갖고 있을 수 있다. PortOne 콘솔에서 누가 언제 취소했는지 조사해 기록하고, 처리 방식은 결정 128대로 실제 사례를 보고 다시 정한다. 현재 구성에는 Alertmanager가 없어(`infra/prometheus/prometheus.yml`에 `alerting` 연결 없음) 경보는 Prometheus 경보 화면(`/alerts`)에만 보이고 silence로 끌 수 없다. 이 경보를 끄는 시스템 동작도 아직 없어, 조사한 건도 계속 firing으로 남는다. 끄는 방법은 Payment `tasks.md`의 `[결정 필요]`(실패 뒤 늦은 전액 취소 처리)로 남아 있다.

## 409가 오면

| 코드 | 뜻 | 할 일 |
|---|---|---|
| `REFUND_CLOSURE_PG_NOT_CANCELLED` | PG가 아직 결제 상태(잔액 있음)·부분 취소·취소 진행 중이다 | 콘솔에서 전액 취소 뒤 3번을 다시 |
| `REFUND_CLOSURE_BUSY` | 자동 PG 조회가 진행 중이다 | 잠시 뒤 같은 요청을 다시 |
| `CLOSURE_PG_UNAVAILABLE` / `CLOSURE_PG_INVALID_EVIDENCE` | PG 조회 장애 / 응답이 원결제와 맞지 않음 | 장애는 회복 뒤 재시도. 근거 불일치는 콘솔 결제·상점·채널을 조사 |
| `REFUND_NOT_CLOSABLE` | 3시간 창 안의 미확정, 이미 성공·실패로 끝남, 이미 종결됨 | 상세의 `executionStatus`·`closure` 확인. 창 안이면 자동 조회를 기다림 |
| `CONFLICT` | 귀속 미확정 건의 PG 취소 내역이 처음 확인한 내역과 다르다 | 종결하지 말고 콘솔 내역을 조사 |
| `REFUND_FAILURE_CLOSURE_PG_NOT_UNCANCELLED` | (실패 종결) PG에 취소액이 있거나 `REQUESTED`·`SUCCEEDED` 취소가 있다. 돈이 움직였을 수 있다 | 실패로 닫지 않는다. 전액 취소면 결정 127 종결, 진행 중이면 결과를 기다림, 일부 취소면 콘솔 내역을 조사 |
| `REFUND_NOT_FAILURE_CLOSABLE` | (실패 종결) 자동 송신이 아직 멈추지 않았거나(3시간 창 안·중단 없음), 송신 중이거나, 이미 성공·실패·종결됐다 | 상세의 `executionStatus`·`failureClosure`·중단 정보를 확인. 창 안이면 자동 송신·조회를 기다림 |
| `REFUND_CLOSURE_CHANGED` | 같은 `requestId`를 다른 내용으로 보냈다 | 새 `requestId`로 보내지 말고 처음 보낸 내용을 확인 |

## 하지 않는 것

- SQL로 `refund_execution`·`refund_closure`·`refund_failure_closure`를 고치지 않는다. 종결 기록은 append-only이고 실행 삭제를 막는다(V39·V40).
- 시간만으로 실패·보유권 복구·새 취소 요청을 만들지 않는다([결정 29](../decisions.md#full-refund-policy) 하위). 실패 종결도 운영자 요청과 PG 재조회 근거로만 한다.
- 실패 종결 뒤 ADMIN이 고객 대신 새 시도를 만들지 않는다(결정 134).
- Order 시도의 조사 표시(`reconciliationRequired`)는 이 종결로 지워지지 않는다. Payment 상세의 `reconciliationRequired`는 종결 뒤 `false`다. Order 쪽 해제는 남은 결정이다(Payment `tasks.md`).
- 현재 구성에는 Alertmanager가 없어 경보를 silence로 끌 수 없다. `RefundConfirmationsDelayed`·`RefundsUnattributed`는 위 종결로 대상이 없어지면 다음 평가에서 풀린다. `RefundCancelledAfterFailure`는 풀 방법이 `[결정 필요]`로 남아 있다.
