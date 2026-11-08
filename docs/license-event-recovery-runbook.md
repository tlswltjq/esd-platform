# 지급·지급 확인·정지·복구·승인 정정의 운영 재처리

[결정 37](decisions.md#license-event-recovery)의 A안 절차와, Kafka에서 원본이 사라진 `SENT` 이벤트의 같은 ID 재발행([결정 129](decisions.md#orphan-license-grant-republish))이다. 지급(`OrderPaid`→`LicenseGranted`)에 더해 환불의 정지(`LicenseSuspensionRequested`→`LicenseSuspended`)·복구(`LicenseRestorationRequested`→`LicenseRestored`)·승인 정정(`LicenseApprovalCorrectionRequested`→`LicenseApprovalCorrected`) 이벤트도 같은 절차를 쓴다([결정 125](decisions.md#license-grant-wait-detection)·129의 범위 확장). 정상적인 일시 장애는 기존 재시도가 처리한다. 여기서는 소진된 DEAD/DLT와 DLT 보존 기간이 지나 사라진 원본을 운영자가 확인하고 원래 이벤트의 처리를 재개한다. 재발행 성공과 업무 완료를 각각 확인한다.

## 1. 대상과 원인 확인

경보 `RefundLicenseGrantsDelayed`([결정 125](decisions.md#license-grant-wait-detection))에서 왔다면 경보의 `status` 라벨로 대기 상태를 보고, Order `GET /api/v1/admin/refunds?status=<그 상태>`의 진입 시각으로 10분 넘게 기다린 환불의 orderNo를 찾는다.

| `status` | 진입 시각 필드 | 기다리는 이벤트 | 먼저 볼 표 행 |
|---|---|---|---|
| `PENDING_LICENSE_GRANT` | `licenseGrantWaitStartedAt` | `LicenseGranted` | "실제 지급 있음, Order 확인 없음" |
| `PENDING_LICENSE_SUSPENSION` | `licenseSuspensionWaitStartedAt` | `LicenseSuspensionRequested` 처리 뒤 `LicenseSuspended` | 정지 행 |
| `PENDING_LICENSE_RESTORATION` | `licenseRestorationWaitStartedAt` | `LicenseRestorationRequested` 처리 뒤 `LicenseRestored` | 복구 행. PG 실패가 확정돼 고객은 돈도 이용권도 없는 상태다 |
| `PENDING_APPROVAL_STOP` | `approvalStopWaitStartedAt` | 정지 요청이 나간 건은 `LicenseSuspended`, 그 뒤 `LicenseApprovalCorrectionRequested` 처리 뒤 `LicenseApprovalCorrected` | 정지 행, 승인 정정 행 |

대상 orderNo, 발견 시각, 담당자, 장애 증거와 원인 해결 내용을 운영 기록에 남긴다. 같은 서비스·DLT 토픽의 재투입은 한 담당자/프로세스가 수행한다. 직접 할당하는 운영 소비자는 컨슈머 그룹 리밸런스로 서로를 배제하지 않기 때문이다.

동일 MySQL 인스턴스에서 두 DB를 읽을 권한이 있다면 [조회 SQL](../tools/operations/license-recovery.sql)을 사용한다. SQL은 읽기 전용 트랜잭션이며 업무·Inbox·Outbox를 수정하지 않는다. DB를 분리한 환경에서는 각 소유 모듈에서 같은 사실을 별도로 조회하고 조회 시각도 기록한다.

```sql
SET @order_no = '대상 주문 UUID';
SOURCE /절대경로/tools/operations/license-recovery.sql;
```

결과가 없으면 DB·주문 번호·조회 권한을 먼저 확인한다. NULL은 상대 기록 미확인이며 일치나 불일치로 간주하지 않는다. 비교 열은 구매자·상품·결제/요청 식별자·불변 스냅샷·확인의 일치를 돕는 자료이고, 실제 재처리에서는 기존 업무 서비스가 원본 계약 전체를 다시 검증한다. PAID만으로 복구 대상을 결정하지 않고 환불·실제 보유권의 정지와 처리 이력도 확인한다.

| 관측 | 다음 확인/행동 |
|---|---|
| 원래 Outbox PENDING, 소비 적체 또는 재시도 중 | 발행·소비 진행을 관측한다. DEAD로 바꾸거나 별도 지급을 만들지 않는다. |
| OrderPaid DEAD | Order의 원래 Outbox ID·주문·원인 대조 후 그 이벤트만 재큐잉한다. |
| OrderPaid SENT, License 지급/Inbox 없음 | License의 `stove.order.v1.DLT`와 정상 소비 지연·중단을 구분한다. DLT에 해당 원본이 있을 때 재투입을 검토한다. |
| 실제 지급 있음, Order 확인 없음 | LicenseGranted의 DEAD 또는 Order의 `stove.license.v1.DLT`를 확인한다. 기존 지급 상태와 licenseId를 유지한다. |
| `LicenseSuspensionRequested`·`LicenseRestorationRequested`·`LicenseApprovalCorrectionRequested` DEAD | Order의 원래 Outbox ID·주문·환불 대기 상태를 대조한 뒤 그 이벤트만 Order에서 재큐잉한다. |
| 위 요청 SENT, License 처리 기록(Inbox) 없음 | License의 `stove.order.v1.DLT`를 확인한다. 원본이 있으면 License에서 재투입을 검토한다. |
| `LicenseSuspended`·`LicenseRestored`·`LicenseApprovalCorrected` DEAD | License의 원래 Outbox ID·주문을 대조한 뒤 그 이벤트만 License에서 재큐잉한다. |
| 위 결과 SENT, Order 처리 기록(Inbox) 없음 | Order의 `stove.license.v1.DLT`를 확인한다. 원본이 있으면 Order에서 재투입을 검토한다. |
| OrderPaid SENT, License 구매 근거·Inbox 없음, `stove.order.v1.DLT`에도 원본 없음 | Order에서 그 `OrderPaid` 한 건을 같은 ID로 재발행한다(3절 재발행 전 확인). License가 지급한 뒤 `LicenseGranted`가 정상 흐름으로 이어진다. |
| LicenseGranted SENT, Order `order_license_grant`·Inbox 없음, `stove.license.v1.DLT`에도 원본 없음 | License에서 그 `LicenseGranted` 한 건을 같은 ID로 재발행한다(3절 재발행 전 확인). `PENDING_LICENSE_GRANT` 환불은 기존 정지 요청으로 이어진다. |
| 정지·복구·승인 정정 요청 SENT, License Inbox 없음, `stove.order.v1.DLT`에도 원본 없음 | Order에서 그 요청 한 건을 같은 ID로 재발행한다(3절 재발행 전 확인). License가 처리하고 결과 이벤트를 낸다. |
| 정지·복구·승인 정정 요청은 License가 처리함(Inbox 있음), 결과 SENT, Order Inbox 없음, `stove.license.v1.DLT`에도 원본 없음 | License에서 그 결과 한 건을 같은 ID로 재발행한다(3절 재발행 전 확인). License는 이미 처리한 요청을 다시 받아도 결과를 다시 내지 않으므로, 요청을 재발행하지 않는다. `LicenseSuspended`는 `PENDING_LICENSE_SUSPENSION`을 PG 실행 요청으로(승인 중단 건은 승인 정정 요청으로), `LicenseRestored`는 `PENDING_LICENSE_RESTORATION`을 `FAILED`로, `LicenseApprovalCorrected`는 `PENDING_APPROVAL_STOP`을 정정 회차(`PENDING_REVIEW`)로 잇는다. |
| Outbox 행도 없음, 구매 근거/Inbox/지급 기록 모순, 받는 쪽 검증 거절, 조회 장애 | 확인 대상으로 남기고 원인을 조사한다. 새 이벤트 ID 생성·SQL 보정·ACTIVE 재설정으로 진행하지 않는다. |

## 2. 접근과 조회

Order·License의 `stove.messaging.ops.enabled` 기본값은 false다. 지정한 인스턴스에서 필요할 때만 `STOVE_MESSAGING_OPS_ENABLED=true`로 명시적으로 활성화하고 정상 재기동·접근 범위를 확인한다. 기존 공유 인스턴스의 설정을 임의로 바꾸지 않는다. Gateway에는 운영 경로가 없으며 서비스 직접 접근에서도 실제 Auth의 ADMIN JWT가 필요하다. MEMBER·익명은 사용할 수 없다.

SSH 터널·루프백 또는 기존 통제된 서비스 내부 접근을 사용한다. JWT를 명령행·운영 기록에 넣지 않고 권한 0600의 curl 설정 파일 등에 보관한다. 아래 `ops_curl_config`는 `header = "Authorization: Bearer ..."`가 든 비공개 파일의 절대 경로이며 `service_origin`은 대상 Order 또는 License의 직접 주소다.

```bash
curl --fail-with-body --silent --show-error --config "$ops_curl_config" \
  "$service_origin/api/v1/ops/outbox/dead"

curl --fail-with-body --silent --show-error --config "$ops_curl_config" --get \
  --data-urlencode 'topic=stove.order.v1.DLT' --data-urlencode 'max=20' \
  "$service_origin/api/v1/ops/dlt"
```

DLT 조회는 커밋하지 않는다. eventId·eventType·key·원본 토픽·실패 원인을 원래 Outbox와 대조한다. Order가 낸 이벤트(`OrderPaid`·정지/복구/승인 정정 요청)는 License에서, License가 낸 이벤트(`LicenseGranted`·정지/복구/승인 정정 결과)는 Order에서 재투입한다. `stove.order.v1`에는 다른 종류의 이벤트도 있으므로 조회한 배치 전체를 확인한다. 본문이 필요한 대조는 제한된 원본 저장소에서 수행하며 JWT·본문·구매자 정보 전체를 운영 로그에 복사하지 않는다.

존재하지 않거나 DLT 형식이 아닌 토픽은 400으로 안내한다. DLT가 아직 만들어지지 않은 경우 정상 소비의 재시도 중인지 먼저 확인하며, 없는 토픽을 미처리 0건이나 복구 완료로 간주하지 않는다.

## 3. 원본 재처리

원인을 해결하고 대상 대조를 마친 뒤 실행한다.

```bash
# 해당 Outbox를 소유한 모듈에서 원래 DEAD eventId 한 건만 회수한다.
curl --fail-with-body --silent --show-error --config "$ops_curl_config" -X POST \
  "$service_origin/api/v1/ops/outbox/dead/$event_id/requeue"

# 아래는 조회·검토한 다음 배치를 License에서 원본 토픽으로 돌려보내는 예다.
curl --fail-with-body --silent --show-error --config "$ops_curl_config" -X POST --get \
  --data-urlencode 'topic=stove.order.v1.DLT' --data-urlencode 'max=20' \
  "$service_origin/api/v1/ops/dlt/replay"
```

### Kafka에서 원본이 사라진 SENT 이벤트의 재발행

DLT 재투입과 DEAD 재큐잉을 쓸 수 없을 때만 쓴다. 발행은 성공해 원래 Outbox 행이 `SENT`이고, 받는 쪽 DLT 레코드가 보존 기간을 넘겨 사라진 경우다. 재발행은 행의 `event_id`·본문·키를 바꾸지 않고 상태만 발행 대기로 돌린다. 새 이벤트 ID를 만들지 않는다. 허용 타입은 소유 모듈별이며([OutboxRepublishTargets](../common/messaging/src/main/java/com/stove/common/messaging/ops/OutboxRepublishTargets.java), 모듈 설정 `stove.messaging.ops.republish-owner`), 각 모듈은 자기가 Outbox로 낸 이벤트만 재발행한다. 다른 모듈 타입은 400이다.

| 소유 모듈(재발행 실행 위치) | 재발행 타입 | 받는 모듈 | 받는 쪽 `processed_event.consumer_group`(코드 상수) | 받는 쪽 DLT |
|---|---|---|---|---|
| Order | `OrderPaid` | License | `license-paid-order` (`GrantLicense.CONSUMER_GROUP`) | `stove.order.v1.DLT` |
| Order | `LicenseSuspensionRequested` | License | `license-suspension` (`ApplyLicenseSuspension.CONSUMER_GROUP`) | `stove.order.v1.DLT` |
| Order | `LicenseRestorationRequested` | License | `license-restoration` (`ApplyLicenseRestoration.CONSUMER_GROUP`) | `stove.order.v1.DLT` |
| Order | `LicenseApprovalCorrectionRequested` | License | `license-approval-correction` (License `ApplyApprovalCorrection.CONSUMER_GROUP`) | `stove.order.v1.DLT` |
| License | `LicenseGranted` | Order | `order-license-grant` (`ApplyLicenseGrant.CONSUMER_GROUP`) | `stove.license.v1.DLT` |
| License | `LicenseSuspended` | Order | `order-refund-suspension` (`ApplyRefundSuspension.CONSUMER_GROUP`, 승인 중단 건도 같은 그룹) | `stove.license.v1.DLT` |
| License | `LicenseRestored` | Order | `order-refund-restoration` (`ApplyRefundRestoration.CONSUMER_GROUP`) | `stove.license.v1.DLT` |
| License | `LicenseApprovalCorrected` | Order | `order-approval-correction` (Order `ApplyApprovalCorrection.CONSUMER_GROUP`) | `stove.license.v1.DLT` |

재발행 전 확인 순서:

1. 소유 모듈 Outbox에 원래 행이 있고 `status = SENT`다. `PENDING`이면 발행을 기다리고, `DEAD`면 위 `requeue`를 쓴다. 행이 없으면 재발행할 수 없으며 1절 마지막 행(조사 대상)으로 간다.
2. 받는 쪽에 그 eventId의 처리 기록이 없다. 위 표의 받는 모듈 `processed_event`에서 `event_id`와 `consumer_group`(표의 값 그대로)을 함께 조회한다. `OrderPaid`는 License 구매 근거, `LicenseGranted`는 Order `order_license_grant`도 함께 본다. 이미 있으면 재발행해도 효과가 없으므로 실행하지 않고 다른 원인을 찾는다. 정지·복구·승인 정정에서 요청은 처리됐는데(License Inbox 있음) 결과를 Order가 받지 못했다면, 요청이 아니라 License의 결과 이벤트를 재발행한다.
3. 받는 쪽 DLT에 그 원본이 없다. 남아 있으면 DLT 재투입을 먼저 검토한다.
4. Order 환불이 그 요청 ID의 대기 상태에 있다(경보에서 왔을 때).
   - `OrderPaid`·`LicenseGranted`: 그 주문의 환불이 `PENDING_LICENSE_GRANT`다.
   - `LicenseSuspensionRequested`·`LicenseSuspended`: `order_refund.suspension_request_event_id`가 요청 ID(결과의 `requestEventId`)와 같고 `PENDING_LICENSE_SUSPENSION`이다. 승인 중단 건은 현재 회차 `order_refund_review_round.stop_suspension_request_id`가 같고 `PENDING_APPROVAL_STOP`이다.
   - `LicenseRestorationRequested`·`LicenseRestored`: 현재 시도(`order_refund.current_attempt_id`)의 `order_refund_attempt.restoration_request_event_id`가 요청 ID와 같고 `PENDING_LICENSE_RESTORATION`이다.
   - `LicenseApprovalCorrectionRequested`·`LicenseApprovalCorrected`: 현재 회차의 `order_refund_review_round.stop_restoration_request_id`가 요청 ID와 같고 `PENDING_APPROVAL_STOP`이다.

   요청 ID가 다르거나 환불이 이미 다른 상태면 실행하지 않는다. 받는 쪽 검증이 거절해 DLT로 가고, 그 건은 조사 대상이다.

```bash
# 해당 Outbox를 소유한 모듈에서 SENT eventId 한 건만 재발행한다. 사유(1~500자)는 필수다.
curl --fail-with-body --silent --show-error --config "$ops_curl_config" -X POST \
  -H 'Content-Type: application/json' --data '{"reason":"<조사 번호와 확인 결과>"}' \
  "$service_origin/api/v1/ops/outbox/$event_id/republish"
```

응답 200은 발행 대기 접수이며 업무 완료가 아니다. 실행자(ADMIN JWT `sub`)·시각·사유·이전 발행 시각은 소유 모듈의 `outbox_republish`에 한 행으로 남는다. 없는 행·`SENT`가 아닌 행은 404, 허용 목록 밖 타입·사유 없음은 400이다. 재발행 뒤에는 Outbox가 다시 `SENT`가 되고 받는 쪽 업무 기록이 생겼는지 4절대로 확인한다. 받는 쪽 검증이 거절해 다시 DLT로 가면 반복하지 않고 조사한다.

DLT API는 미처리 배치의 앞에서 최대 max개를 재투입한다. 특정 eventId 한 건만 고르는 API가 아니다. 토픽 간 전체 순서는 없고 조회 후 새 레코드 유입·보존기간 변화도 가능하므로 배치 전체의 원인 해결을 확인할 수 없다면 실행하지 않는다. Order·License는 일괄 Outbox 회수 경로 `requeue-all`을 허용하지 않으며 건별 경로를 사용한다.

성공 응답의 `replayed`는 Kafka가 재발행을 확인한 건수이며 최종 업무 성공 건수가 아니다. 실제 재발행한 레코드까지만 파티션별 오프셋을 커밋한다. 발행 실패·미확정·중단은 성공으로 응답하지 않고 배치의 오프셋을 커밋하지 않는다. 일부가 이미 발행된 뒤 응답 유실/중단된 경우 재실행은 중복 전달될 수 있으므로 원래 ID를 보존하고 기존 멱등 처리를 사용한다. 운영 소비자의 earliest는 남아 있는 Kafka 보존 범위에서 적용되며 백업 복원을 뜻하지 않는다.

HTTP 실패·응답 유실이면 성공/실패를 추정하지 말고 원래 이벤트의 발행/소비·업무 상태와 DLT 커서를 다시 확인한다. 같은 원인으로 다시 실패하면 추가 재투입 전에 원인을 조사한다. 자동·무제한 반복은 하지 않는다.

## 4. 업무 완료와 종료 기록

재처리 전후의 조회 결과와 다음 사실을 대조한다.

- 같은 원구매의 보유권은 한 건이고 기존 licenseId·구매 근거를 보존한다. 기존 정지 상태가 임의로 ACTIVE가 되지 않는다.
- Order의 지급 확인은 해당 원본 LicenseGranted와 일치하고 같은 Inbox가 중복되지 않는다.
- 정상 ACTIVE 고객은 기존 Library/Download API로 확인한다. 환불이 지급 확인을 기다렸다면 확인 이후 기존 정지 요청이 연결됐는지 확인한다. 실제 정지가 이뤄진 건의 다운로드 차단도 확인한다.
- 정지 대기였다면 PG 실행 요청(`RefundExecutionRequested`)이, 복구 대기였다면 `FAILED`와 실제 보유권 복구가, 승인 중단 대기였다면 정정 회차(`PENDING_REVIEW`)가 이어졌는지 확인한다. 경보의 해당 `status` 시계열이 0으로 돌아왔는지도 본다.
- Outbox SENT나 DLT 커서 전진만으로 완료하지 않는다. 재발행 성공 뒤 다시 DLT에 들어간 건도 확인한다. `SENT` 재발행이면 소유 모듈 `outbox_republish`의 행과 받는 쪽 Inbox 한 건을 함께 대조한다.

기록에는 대상 주문·원래 eventId·DLT 토픽/파티션/오프셋, 실행 모듈·담당자·실행 시각·해결 사유, 응답의 재투입 수와 업무 결과·다음 행동을 남긴다. 전용 운영 인스턴스·터널·비공개 인증 파일은 기존 운영 절차에 따라 정리하고 활성화 설정을 원래 상태로 되돌린다.
