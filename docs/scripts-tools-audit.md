# scripts 정리 조사 (2026-10-21)

## 범위와 결론

현재 작업 트리 기준 `scripts/`는 문서를 포함해 49개 파일, 5,000줄이다. 검증 구현은 이미 `tools/verification/`으로 이동했으며 `scripts/`에는 호환 래퍼 11개가 남아 있다. 이번 조사는 파일·호출 관계·문서·완료 모듈의 계약을 정적으로 대조했다. 조사 과정에서 스크립트 실행, 파일 삭제, 추가 이동은 하지 않았다.

핵심은 구형 업무 시나리오 제거와 재사용 가능한 실행 도구의 분리다. `perf/`, `chaos/`를 통째로 현행 도구로 옮기면 현재 계약과 맞지 않는 시나리오도 유지하게 된다. 과거 측정 결과는 문서와 Git 이력으로 보존하고, 현재 실행 경로에서는 구형 시나리오를 제거하는 방향을 권장한다.

| 묶음 | 파일 수 | 줄 수 |
|---|---:|---:|
| 검증 호환 래퍼 | 11 | 132 |
| 루트의 나머지 파일 | 12 | 950 |
| image-benchmark 하위 파일 | 3 | 766 |
| db-lab | 3 | 348 |
| perf | 14 | 1,864 |
| chaos | 6 | 940 |

`image-benchmark.py`는 루트 묶음에 포함한다. 줄 수에는 주석과 문서가 포함된다.

## 삭제 후보

| 대상 | 근거 | 함께 정리할 참조 |
|---|---|---|
| `scripts/verify-*.py` 10개, `scripts/verification/auth-bff.py` | 새 구현을 호출하는 12줄짜리 호환 래퍼뿐이다. 저장소 내 명령을 새 경로로 통일하면 필요 없다. | README, 모듈별 실행 안내, 계획 문서의 실행 명령. 과거 명령 기록은 당시 명령임을 표시하거나 이력 링크 제공 |
| `scripts/api-demo.sh` | `PG_CALLBACK_SECRET`, 고정 57개 검증, 할인·환불·구 다운로드 여정을 전제로 한다. 현재 전체 여정은 Settlement/Jib 검증으로 확인한다. | `remote.sh demo`, `local-stack.sh demo-*`, `docs/api-demo.md`, `e2e:apiDemoTest` 및 구형 E2E 테스트 의존성 |
| `scripts/perf/{smoke,order-throughput,order-soak,payment-callback}.js`, `scripts/perf/lib/config.js` | `items: [{productId, quantity}]`, 고정 상품 1·2, 구 견적·결제 prepare/callback API에 의존한다. | 아래 성능 실행기, `docs/performance.md`, `docs/measuring.md`, perf README |
| `scripts/perf/{run-session,run-condition,run-isolation-ab}.sh` | 위 구형 부하를 실행하며 `orders`, `order_item`, 구 License/Payment 테이블을 기준으로 초기화·계수한다. | 과거 성능 실험 명령·상대 링크 |
| `scripts/chaos/{commerce-auth,fault,run-scenario,recover-license}.sh` | JWT `member_id`, 구 주문·결제 callback, `stove_license.license`, `CANCELED`/환불 및 구 원장 재생에 의존한다. | `docs/chaos.md`, `docs/resilience-scenarios.md`, chaos README 및 서로 간 source 호출 |

삭제는 위 참조 정리와 한 변경으로 수행한다. 특히 `apiDemoTest`는 태스크만 지워 끝나는 일이 아니다. 어떤 테스트가 구형 데모에만 속하는지 확인하고 현행 모듈별 E2E가 보존되는지 확인해야 한다.

### 현재 계약과의 구체적인 차이

- `apps/order/.../api/controller/dto/CreateOrderRequest.java`: `requestId`, `productCode`, `expectedContentVersion`, `expectedAmount`를 받는다. 기존 부하 스크립트의 `items` 요청과 다르다.
- `apps/payment/.../api/controller/PaymentController.java`: 내 결제 목록·상세 조회를 제공한다. 기존 도구의 `/prepare`, `/callback`은 현재 컨트롤러에 없다. 현재 승인 공급자는 독립 `simulators/mock-pg`다.
- 현재 구매자는 JWT `sub`로 식별하며, Order는 같은 구매자의 동일 상품 PENDING·PAID 중복 주문을 막는다. 요청 형태만 수정해도 고정 구매자·상품 부하는 정상 주문 처리량을 측정하지 못한다.
- `docs/order-guide.md`는 `orders`, `order_item`을 보존된 구 테이블로 명시한다. 이 테이블을 비우거나 세는 도구를 현재 주문 검증에 사용할 수 없다.
- `docs/license-guide.md`는 환불·회수를 후속 범위로 명시한다. 구 장애 실험의 환불/취소 판정은 현재 완료 범위의 기준이 아니다.

## tools 이동 후보

| 현재 파일 | 제안 위치 | 이동 시 필요한 정리 |
|---|---|---|
| `image-benchmark.py`, `image-benchmark/{worker,runtime,report}.py` | `tools/image-benchmark/` | 진입점과 하위 파일 통합, 저장소 루트·worker 경로 수정, 실행 문서 수정 |
| `perf/{collect-outbox,collect-consumer,collect-lag}.sh` | `tools/observability/` | 현재 컨슈머·그룹·접속 주소 기본값 대조. Download는 Kafka 컨슈머가 아니므로 기존 기본 목록 수정 |
| `chaos/watch-alerts.sh` | `tools/observability/` | 현재 License 그룹과 알람 이름 대조. 기본 `GROUP=license`를 현재 설정과 맞춰야 함 |
| `db-lab/{run.sh,schema.sql,README.md}` | `tools/db-lab/` | 일반 Outbox/Inbox 락 실험과 과거 Order 만료 실험을 구분. 스키마가 현재 최종 형태라는 설명 수정 |
| `migrate-mock-pg.py` | `tools/migrations/mock-pg/` | V11 내장 PG에서 외부 PG로 복사하는 명시적 도구임을 문서화하고 안내 경로 수정 |
| `catalog-transition.sql`, `order-transition.sql`, `payment-transition.sql`, `license-merge-preflight.sql`, `store-schema.sql` | `tools/migrations/` 하위 모듈별 디렉터리 | 적용 전 버전·중지 조건 명시, 모듈 안내 문서의 링크 수정 |
| `remote.sh` | `tools/remote/remote.sh` | 상대 루트 계산 변경, 삭제 대상 demo/구형 gate 호출 정리. SSH·rsync 도구로 보존하되 현행 로컬 검증의 필수 경로로 안내하지 않음 |

이미지 벤치마크는 고정 과거 revision을 `git archive`하고 당시 Jib 설정을 가져오는 재현 도구다. 내부 `Dockerfile` 참조는 과거 소스를 대상으로 하므로 현재 저장소에서 Dockerfile이 없어졌다는 이유만으로 삭제할 대상이 아니다. `docs/jib-oci-comparison.md`가 재현 절차와 결과를 참조한다. 원격 실행은 현재 로컬 검증 원칙과 구분해 과거 실험용임을 명시한다.

`db-lab`의 실험 4는 구 `orders`의 EXPIRED/PAID 경합이다. Outbox/Inbox 실험은 현재 공통 코드와 연결되지만 이 Order 실험은 현재 업무 검증이 아니다. 일반 DB 학습 실험으로 남길 수 있다. 고정 컨테이너 이름 `stove-db-lab`도 실행별 격리 방식으로 바꾸는 것이 적절하다.

전환 SQL·PG 이관 도구는 삭제를 권하지 않는다. `docs/license-mvp-plan.md`, `docs/license-guide.md`, `docs/settlement-guide.md`는 공유 자료의 전환 완료를 보장하지 않는다. `store-schema.sql`은 초기화 SQL과 내용이 겹치지만 기존 개발 DB의 Store 스키마 추가 절차로 문서에서 사용한다.

## 그대로 옮기기 전에 수정할 파일

### stack-wait.sh

실제 사용처는 `local-stack.sh demo-up`, `remote.sh stack up/gate`, `api-demo.sh`다. 따라서 참조 없는 파일로 삭제할 수 없다.

현재는 `stove_default` 네트워크, 고정 컨테이너 이름, 구 인프라 12종을 전제로 하고 Elasticsearch까지 필수로 검사한다. 앱 목록에는 외부 mock-pg가 없고 PG DB도 기대 인프라 집합에 없다. 전체 Docker 컨테이너의 starting/unhealthy 상태를 모아 판정하므로 다른 검증 스택에도 영향을 받는다. 제거된 quote API의 404 확인만으로 현행 내부 API 차단 계약을 검증할 수도 없다.

현재 Compose 프로젝트·서비스를 대상으로 하는 상태 검사로 교체한 뒤 `tools/verification/`으로 옮기거나 `start-apps.sh`의 `--wait`와 별도 계약 검증으로 역할을 나누는 것이 적절하다. 기존 16개 검사를 그대로 옮기는 것은 권하지 않는다.

### perf/relay-drain.sh, perf/relay-off.override.yml

릴레이 자체 실험 목적은 유효하다. 다만 drain은 기본 `stove_order.outbox_event`를 TRUNCATE하고 구 `OrderCreated`/`stove.order.v1` 이벤트를 적재한다. 현행 공유 스택에서 그대로 실행하는 도구로 승격해서는 안 된다. 실험 전용 DB·토픽·Compose 프로젝트로 바꾸고 현재 설정 키를 대조한 뒤 `tools/experiments/outbox/`로 분리하거나, 당장 실험 수요가 없다면 과거 재현 대상으로 정리한다.

## scripts에 남길 범위

- `local-stack.sh`: 로컬 스택 up/down/status/logs 진입점. 구 데모 제거 시 `demo-*` 분기도 함께 정리한다.
- `start-apps.sh`: 12개 실행 앱을 의존 순서대로 시작하는 공통 실행 조각. Compose 문서, 로컬·원격 실행기, `tools/verification/verify-jib.py`가 실제 사용한다.

권장 최종 구조는 다음과 같다. 필요하지 않은 디렉터리를 미리 만드는 것은 범위에 포함하지 않는다.

```text
scripts/
  local-stack.sh
  start-apps.sh
tools/
  verification/
  image-benchmark/
  observability/
  db-lab/
  migrations/
  remote/
  experiments/outbox/   # 격리 실행을 구현할 때 추가
```

## 진행 순서와 검증

1. 구형 데모·성능·장애 시나리오와 실제 호출부를 제거한다. 과거 결과 문서는 보존하고 재현 revision·당시 경로를 연결한다.
2. 전환 도구·벤치마크·관측 수집기·DB 실험·원격 도구를 목적별로 이동한다. 현재 계약에 맞지 않는 기본값과 문구를 함께 수정한다.
3. 검증 명령을 `tools/verification/`으로 통일하고 호환 래퍼 11개를 제거한다. 저장소 외부의 개인 명령·자동화 사용 여부는 이번 정적 조사로 확인할 수 없다.
4. 구 stack gate를 현재 Compose 기준으로 교체한다. `local-stack.sh`, `start-apps.sh`를 남긴다.
5. 파일 참조·상대 경로·셸/Python 문법, 도구 도움말을 확인한다. 로컬 OrbStack의 격리 Jib/Settlement 검증으로 현행 구매·다운로드·정산 흐름과 정리를 확인한다. 데이터 전환 SQL은 실제 공유 DB에 실행하지 않고 전환 전 스키마의 별도 픽스처로 검증한다.

구형 도구를 현재 계약에 맞춰 모두 다시 구현하는 작업은 이번 정리와 분리한다. 성능·장애 실험을 다시 수행할 때 그 목적에 맞는 최소 시나리오를 새로 정의한다.

## 실행 결과 (2026-10-21)

조사 이후 정리를 적용했다. `scripts/`에는 `local-stack.sh`, `start-apps.sh` 두 파일만 남겼다. 제안한 도구 이동, 호환 래퍼 제거, 구형 데모·성능·장애 시나리오 제거와 문서 경로 갱신을 수행했다. 릴레이 직접 적재 실험은 현행 도구로 재구현하지 않고 과거 Git 이력으로 보존했다.

구 `apiDemoTest`와 데모 전용 `ApiDemoEvidenceTest`를 제거했다. 다른 기존 Java E2E는 이번 스크립트 정리에서 일괄 삭제하지 않았으며, 현행 검증은 모듈별 태그 태스크와 Jib 검증이 담당한다. 원격 `e2e` 명령도 격리 Jib 검증으로 연결했다.

`stack-status.py`는 Compose 프로젝트별 서비스 검사로 교체했으며 외부 PG·PG DB를 포함한다. 임의의 다른 프로젝트 컨테이너를 집계하지 않는다. 초기화 작업의 성공 종료를 별도 판정하고, 헬스체크가 없는 서비스는 실행 상태만 확인한다. 이전 내부 quote API 404 검사는 대체 기능 검증으로 주장하지 않는다.

DB 실험은 실행별 컨테이너 이름을 사용한다. 이관 도구는 기존 SQL/PG 이관 통합 테스트의 경로를 함께 수정했다. 실제 공유 자료 전환이나 원격 배포는 수행하지 않았다.

### 정리 후 검증

- `python3 -B tools/verification/verify-jib.py --skip-build`: 새 상태 검사, 앱 12개 이미지·기동 설정, 구매·다운로드·배분 격리·PG 지급 수령 재시도, 12개 정상 종료와 격리 자원 정리 통과. 결과: `runs/jib-validation-7hmyv5ve/result.json`. 기존 로컬 이미지를 사용했으며 이미지 재빌드는 이번 검증에 포함하지 않았다.
- Catalog·Order·Payment의 `*MigrationIntegrationTest`: 각각 1·4·2건, 총 7건 통과. 이동한 SQL과 PG 이관 프로그램을 격리 DB에서 실행했다. 로그: `runs/tools-migration-tests.log`.
- `test_stack_status.py`: 누락 서비스, 비정상 복제본, 초기화 작업 실패, 프로젝트별 조회 범위에 대한 4건 통과.
- `bash tools/db-lab/run.sh 1`: RR/RC 락 실험 완료와 실험 컨테이너 정리 확인. 로그: `runs/tools-db-lab.log`.
- Python·셸 문법, 저장소 밖에서의 Python 도구 도움말, 원격 도구 도움말, 전환 파일 내용 보존, `git diff --check` 확인. 로컬 문서 링크 검사에서 기존 미보존 `runs` 링크 2개 외에 누락 없음.
- 원격 벤치마크·원격 동기화·공유 DB 전환은 실행하지 않았다.
