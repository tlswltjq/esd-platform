# 지급·지급 확인의 운영 재처리

[결정 37](decisions.md#license-event-recovery)의 A안 절차다. 정상적인 일시 장애는 기존 재시도가 처리한다. 여기서는 소진된 DEAD/DLT를 운영자가 확인하고 원래 이벤트의 처리를 재개한다. 재발행 성공과 업무 완료를 각각 확인한다.

## 1. 대상과 원인 확인

경보 `RefundLicenseGrantsDelayed`([결정 125](decisions.md#license-grant-wait-detection))에서 왔다면 Order `GET /api/v1/admin/refunds?status=PENDING_LICENSE_GRANT`의 `licenseGrantWaitStartedAt`으로 10분 넘게 기다린 환불의 orderNo를 찾고, 아래 표의 "실제 지급 있음, Order 확인 없음" 행부터 확인한다.

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
| 원본 없음, 구매 근거/Inbox/지급 기록 모순, 조회 장애 | 확인 대상으로 남기고 원인을 조사한다. 새 이벤트 ID 생성·SQL 보정·ACTIVE 재설정으로 진행하지 않는다. |

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

DLT 조회는 커밋하지 않는다. eventId·eventType·key·원본 토픽·실패 원인을 원래 Outbox와 대조한다. OrderPaid는 License에서, LicenseGranted는 Order에서 재투입한다. `stove.order.v1`에는 다른 종류의 이벤트도 있으므로 조회한 배치 전체를 확인한다. 본문이 필요한 대조는 제한된 원본 저장소에서 수행하며 JWT·본문·구매자 정보 전체를 운영 로그에 복사하지 않는다.

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

DLT API는 미처리 배치의 앞에서 최대 max개를 재투입한다. 특정 eventId 한 건만 고르는 API가 아니다. 토픽 간 전체 순서는 없고 조회 후 새 레코드 유입·보존기간 변화도 가능하므로 배치 전체의 원인 해결을 확인할 수 없다면 실행하지 않는다. Order·License는 일괄 Outbox 회수 경로 `requeue-all`을 허용하지 않으며 건별 경로를 사용한다.

성공 응답의 `replayed`는 Kafka가 재발행을 확인한 건수이며 최종 업무 성공 건수가 아니다. 실제 재발행한 레코드까지만 파티션별 오프셋을 커밋한다. 발행 실패·미확정·중단은 성공으로 응답하지 않고 배치의 오프셋을 커밋하지 않는다. 일부가 이미 발행된 뒤 응답 유실/중단된 경우 재실행은 중복 전달될 수 있으므로 원래 ID를 보존하고 기존 멱등 처리를 사용한다. 운영 소비자의 earliest는 남아 있는 Kafka 보존 범위에서 적용되며 백업 복원을 뜻하지 않는다.

HTTP 실패·응답 유실이면 성공/실패를 추정하지 말고 원래 이벤트의 발행/소비·업무 상태와 DLT 커서를 다시 확인한다. 같은 원인으로 다시 실패하면 추가 재투입 전에 원인을 조사한다. 자동·무제한 반복은 하지 않는다.

## 4. 업무 완료와 종료 기록

재처리 전후의 조회 결과와 다음 사실을 대조한다.

- 같은 원구매의 보유권은 한 건이고 기존 licenseId·구매 근거를 보존한다. 기존 정지 상태가 임의로 ACTIVE가 되지 않는다.
- Order의 지급 확인은 해당 원본 LicenseGranted와 일치하고 같은 Inbox가 중복되지 않는다.
- 정상 ACTIVE 고객은 기존 Library/Download API로 확인한다. 환불이 지급 확인을 기다렸다면 확인 이후 기존 정지 요청이 연결됐는지 확인한다. 실제 정지가 이뤄진 건의 다운로드 차단도 확인한다.
- Outbox SENT나 DLT 커서 전진만으로 완료하지 않는다. 재발행 성공 뒤 다시 DLT에 들어간 건도 확인한다.

기록에는 대상 주문·원래 eventId·DLT 토픽/파티션/오프셋, 실행 모듈·담당자·실행 시각·해결 사유, 응답의 재투입 수와 업무 결과·다음 행동을 남긴다. 전용 운영 인스턴스·터널·비공개 인증 파일은 기존 운영 절차에 따라 정리하고 활성화 설정을 원래 상태로 되돌린다.
