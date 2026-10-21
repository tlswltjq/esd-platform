# Settlement·외부 모의 PG 실행 안내

[문서 목록](README.md) · [범위·검증 기록](settlement-mvp-plan.md) · [Payment](payment-guide.md) · [정산 모델](domain/models/settlement.md)

Settlement는 결제 완료 주문의 판매자 매출·배분·월 마감과 PG 지급 명세 대사를 담당한다.
이번 범위의 수령 완료는 모의 PG 보고와 내부 원장이 일치했다는 의미다. 실제 계좌 입금·판매자 송금은 수행하지 않는다.

## 격리된 로컬 실행

OrbStack을 시작하고 저장소 루트에서 실행한다. 기본 앱 포트 오프셋은 33000이다.

```sh
python3 tools/verification/verify-settlement.py
```

스크립트가 열한 업무 앱과 외부 모의 PG를 로컬 JVM으로 실행하고 MySQL·Redis·Kafka·MinIO 및 별도 PG MySQL을 전용 Compose 프로젝트로 생성한다.
로그·명세 번호·금액·단계별 JUnit 결과는 출력된 `runs/stove-settlement-check-*` 아래에 보존한다.
완료·실패 시 해당 실행의 프로세스·컨테이너·볼륨을 정리한다. 공유 스택과 볼륨은 사용하지 않는다.
`SETTLEMENT_TEST_PORT_OFFSET`으로 업무 앱 포트 범위를 변경할 수 있다. PG와 인프라 포트는 빈 포트를 선택한다.

기본 Compose와 Jib 이미지의 컨테이너 배포를 확인하려면 `python3 tools/verification/verify-jib.py`를 실행한다.
전용 네트워크·DB·포트에서 12개 앱의 health·사용자·JVM 옵션과 출시·구매·ZIP 다운로드·배분·PG 수령·정상 종료를 검증한다.
완료 후 해당 스택만 정리하고 `runs/jib-validation-*`에 로그와 단계별 JUnit 결과를 보존한다.

직접 단위·통합 검사를 실행할 때는 Testcontainers에 OrbStack 주소를 전달한다.

```sh
export DOCKER_HOST="$(docker context inspect orbstack --format '{{.Endpoints.docker.Host}}')"
./gradlew :simulators:mock-pg:test :simulators:mock-pg:integrationTest \
  :apps:payment:test :apps:payment:integrationTest \
  :apps:settlement:test :apps:settlement:integrationTest \
  -Pstove.infraTest.maxParallel=1 --no-daemon
```

## 직접 기동하는 경우의 설정

| 프로세스 | 환경변수 |
|---|---|
| 외부 모의 PG | MOCK_PG_DB_HOST·PORT·NAME·USER·PASSWORD, MOCK_PG_PAYMENT_KEY·SETTLEMENT_KEY·CONTROL_KEY |
| Payment | MOCK_PG_URI, MOCK_PG_PAYMENT_KEY |
| Settlement | MOCK_PG_URI, MOCK_PG_SETTLEMENT_KEY, CATALOG_BASE_URL, CATALOG_SETTLEMENT_SERVICE_KEY |
| Catalog | CATALOG_SETTLEMENT_SERVICE_KEY |

PG의 세 키는 모두 필수이며 서로 달라야 한다. Payment와 Settlement에는 자기 역할의 키만 제공한다.
제어 키는 검증 운영자·스크립트에만 제공한다. PG DB 자격증명은 PG 프로세스만 사용한다.
PG 비용은 기본 300bps, 신규 결정은 APPROVED다. 검증용으로 MOCK_PG_FEE_RATE_BPS와 MOCK_PG_DECISION=DECLINED를 지정할 수 있다.
변경은 새 결제번호에만 적용되고 기존 결정·비용·명세는 그대로 유지된다.
요율을 변경할 때는 MOCK_PG_FEE_POLICY_ID도 새 버전으로 지정한다. 같은 정책 ID의 다른 요율은 새 결정 생성을 거부한다.
플랫폼 배분 정책은 고정 1000bps이며 PG 비용은 플랫폼 전액 부담이다.

모의 PG를 단독 기동할 때는 세 키와 전용 DB를 준비한 뒤 `./gradlew :simulators:mock-pg:bootRun`을 사용한다.
Compose 앱 구성에는 `mock-pg`와 별도 `pg-mysql`이 포함된다. MOCK_PG_DB_ROOT_PASSWORD와 MOCK_PG_DB_PASSWORD도 명시한다.
이 DB는 업무 MySQL·자격증명·볼륨과 별개이며 PG 제어 경로는 Gateway로 라우팅하지 않는다.

## 매출·마감·PG 수령

CREATOR는 JWT sub에 해당하는 두 GET만 사용한다.

```sh
curl -H "Authorization: Bearer $CREATOR_TOKEN" "$GATEWAY/api/v1/settlements/me/sales"
curl -H "Authorization: Bearer $CREATOR_TOKEN" "$GATEWAY/api/v1/settlements/me/months"
```

ADMIN은 `/sales`, `/months`의 sellerSubject·month 필터로 조회하고
`POST /api/v1/settlements/sellers/{sellerSubject}/months/{yyyy-MM}/close`로 지난 KST 월을 마감한다.
현재·미래 월은 409다. 마감 후 도착한 매출은 현재 월에 반영하고 원래 매출월을 보존한다.
CLOSED는 금액 마감이며 판매자 지급 완료가 아니다. PG 수령과 월 마감은 별도로 수행한다.

검증 운영자는 외부 PG의 `POST /test-support/v1/settlement-runs`에 제어 키와 UUID requestId를 보내 회차를 만든다.
반환된 providerSettlementId를 ADMIN이 `POST /api/v1/settlements/pg-receipts` 본문에 전달한다.
플랫폼은 이미 있는 명세를 GET으로 읽으며 지급 회차를 생성하지 않는다. 운영자가 금액·수령 상태를 입력하지 않는다.

| 수령 상태 | 의미·후속 동작 |
|---|---|
| REPORTED_PAID | 공급자 지급 보고 접수. 미도착 매출을 기다림 |
| MISMATCH | 도착한 내부 거래의 연결·금액·비용 불일치. 원본을 대조하고 근거 보존 |
| SIMULATED_RECEIVED | 모든 거래가 일치해 모의 수령 확정. 최초 시각 유지 |

명세 조회는 ADMIN의 `/pg-receipts`, `/pg-receipts/{providerSettlementId}`다.
미도착 매출이 반영되면 `POST /pg-receipts/{providerSettlementId}/reconcile`로 저장한 명세를 재대사한다.
재대사는 PG·Catalog·Payment가 중단되어도 가능하다. 신규 매출은 PG·Catalog 근거가 필요하다.
Kafka 재시도 소진으로 DLT에 간 매출은 원인 해소 후 원래 키·본문·이벤트 ID·종류를 유지해 재전달한다.
같은 입력·명세·대사 재시도는 금액을 중복 반영하지 않는다.

## 기존 내장 PG 이관

아래 도구는 오프라인 운영 도구다. 구 Payment의 처리·내장 PG 쓰기를 중지한 뒤 실행한다.
기존 원장·Outbox·Inbox·소비 그룹·DLT를 보존하고 대상과 시작점을 확인한다. 공유 스택은 검증 스크립트가 이관하지 않는다.

```sh
export SOURCE_MYSQL_PASSWORD='source-password'
export TARGET_MYSQL_PASSWORD='target-password'
python3 tools/migrations/mock-pg/migrate.py \
  --source-container OLD_PAYMENT_MYSQL --source-db stove_payment \
  --target-container NEW_PG_MYSQL --target-db mock_pg
```

소스는 V11의 simulated_payment_charge만 읽는다. 원래 결제번호·주문번호·거래 ID·결정·시각·사유를 보존하고 비용은 미산정으로 둔다.
동일 행은 재실행 시 건너뛰고 다른 값은 충돌로 중단한다. 새 PG 원장 저장과 소스 불변 여부를 대조한다.
미산정 비용 거래는 새 정산 명세에서 제외한다. 현재 요율로 소급 계산하지 않는다.
Payment를 처리 비활성 상태로 HTTP에 연결하고 원래 키의 조회·PENDING 복구·미발행 결과를 대조한 뒤 처리기를 재개한다.
구 내장 PG와 외부 PG에 동시에 쓰지 않는다.
