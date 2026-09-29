# P3 전체 API 데모 (#81)

첫 범위는 BASIC / Windows / 한국 / KRW다. 이 절차는 새 스택에서 가입부터 월 마감까지 두 번 연속 실행하고, 각 실행의 식별자와 금액을 JSON으로 남긴다. 실제 결제는 #78의 인증된 시뮬레이터를 사용한다.

## 실행

Docker Compose 2.24 이상, JDK 21, `openssl`이 필요하다. 로컬 18080~18091, 19000, 13200 포트를 사용할 수 있어야 한다. 저장소 루트에서 실행한다.

```bash
bash scripts/api-demo.sh up
bash scripts/api-demo.sh run
bash scripts/api-demo.sh status
bash scripts/api-demo.sh down
```

`up`은 인프라와 앱 JAR를 빌드해 23개 컨테이너를 시작하고 `stack-wait.sh`의 16개 상태 검사를 통과해야 끝난다. 인프라와 앱은 CI 설정으로 호스트 공개 포트를 닫고, E2E에 필요한 포트만 `127.0.0.1`에 연다. 상태 확인은 `status`, 로그 확인은 `bash scripts/local-stack.sh logs <서비스>`로 한다. `down`은 볼륨을 보존한다.
이미 앱 스택이 실행 중이고 이 데모의 비밀 파일이 없다면 `up`은 인증 값을 바꾸지 않고 멈춘다. 기존 스택은 `run`으로 검증하거나 별도 새 환경에서 `up`을 실행한다.

`run`은 스택 검사, 전체 서비스 단위·통합 테스트와 OpenAPI 계약 테스트, 전체 API 여정 두 회차를 순서대로 실행한다. 서비스 테스트는 로컬 캐시의 `UP-TO-DATE` 판정을 쓰지 않고 실제 실행한다. 각 회차의 테스트 57건이 모두 실행되고 건너뛴 항목이 없어야 성공한다. 실패하면 그 자리에서 종료하며, 다음 회차를 통과로 기록하지 않는다. Testcontainers용 `DOCKER_HOST`가 없으면 현재 Docker context에서 읽는다.

결과는 `build/api-demo/<UTC 시각>-<PID>/`에 남는다. `summary.md`와 `stack-gate.log`, `service-tests.log`, `run-1.log`, `run-2.log`, `run-1.json`, `run-2.json`을 확인한다. 다른 경로가 필요하면 `API_DEMO_OUTPUT_DIR`을 지정한다. 두 JSON의 `productCode`가 같으면 게이트가 실패한다.

`up`은 리뷰어·운영자 테스트 계정 비밀번호와 PG 콜백 비밀을 생성해 Git이 무시하는 `build/api-demo/stack-secrets.env`에 소유자만 읽도록 저장한다. `run`이 같은 값을 읽어 로그인과 서명 검증에 쓴다. 파일을 지운 뒤 앱 컨테이너만 재사용하면 인증 값이 달라지므로 다시 `up`을 실행한다. 창작자·구매자 계정은 회차마다 생성하며 비밀번호나 토큰을 결과 JSON에 기록하지 않는다.

원격 호스트는 기존 [remote.sh](../scripts/remote.sh)의 설정을 사용한다.

```bash
./scripts/remote.sh stack up
./scripts/remote.sh demo
./scripts/remote.sh stack status
./scripts/remote.sh stack down
```

원격 `demo`의 결과 디렉터리는 원격 작업본의 `build/api-demo/`에 있다. 원격 작업본의 Git 제외 파일 `.env`에 `AUTH_REVIEWER_PASSWORD`, `AUTH_ADMIN_PASSWORD`, `PG_CALLBACK_SECRET`을 설정하면 Compose와 데모 태스크가 같은 값을 읽는다. `remote.sh sync`는 이 파일을 보존한다. 로컬 `api-demo.sh up`은 테스트 비밀을 자동 생성한다.

## 여정과 판정

| 단계 | 자동 판정 | 실패할 때 확인할 곳 |
|---|---|---|
| 가입·역할 | 새 CREATOR·MEMBER 가입과 OIDC 로그인, REVIEWER·ADMIN 로그인, 타인 데이터 403/404 | `run-N.log`, `auth`·`gateway` 로그 |
| 게시물·파일 | 실제 PNG를 올려 다시 다운로드, Windows ZIP을 multipart 업로드, SHA-256·악성코드·manifest 검증 후 `VALIDATED` | `studio`·`review`·`minio` 로그, CI build 상태 API |
| 심사·출시 | 상점·가격·등급 revision, 수정 요청과 재제출, 필수 심사 승인, `ReleasePublished`의 catalog/store/download 전파 | Studio 제출 review-status, Review case history, `catalog`·`store` 로그 |
| 검색·구매 | 고유 상품코드와 제목 검색, 정가 18,000원, 서버 주문·PG 사전등록·시뮬레이터 승인액 일치 | `/api/v1/storefront/products`, `/api/v1/orders/{orderNo}`, `/api/v1/payments/{orderNo}` |
| 지급·환불 | 라이브러리·티켓 부여, 서명 URL에서 실제 ZIP을 받아 원본 SHA-256 대조, 환불 후 주문·권한·원장 역분개 | `/api/v1/library`, 다운로드 티켓, 주문별 정산 원장, `license`·`download` 로그 |
| 할인·정산 | 판매자/플랫폼 할인 모두 16,000원 청구, 각기 다른 정산 기준·수수료·지급액, 월 마감 후 환불 조정, 대사·CSV | `/api/v1/settlements/reconciliation?month=YYYY-MM`, `/api/v1/settlements/export.csv?month=YYYY-MM`, `settlement` 로그 |
| 서비스 상태 | 16개 스택 게이트, Outbox 적체 0, DLT 유입 0, 결제 trace 전파 | `stack-gate.log`, `run-N.log`, 서비스 actuator/Tempo |

두 할인 주문의 기준값은 아래와 같다. 모두 같은 정가와 2,000원 할인을 사용한다.

| 부담 주체 | 고객 청구 | 정산 기준 | 수수료 30% | 판매자 지급 | 플랫폼 판촉비 |
|---|---:|---:|---:|---:|---:|
| 판매자 | 16,000원 | 16,000원 | 4,800원 | 11,200원 | 0원 |
| 플랫폼 | 16,000원 | 18,000원 | 5,400원 | 12,600원 | 2,000원 |

`run-N.json`은 한 실행의 `gameId`, `productId`, `buildId`, `releaseId`, ZIP SHA-256, 일반 구매와 두 할인 주문번호, 행사 ID, 판매자 ID, 월, 원장 금액, 마감 원본과 조정액, 대사 경고를 담는다. `downloadedObjectMatchesSha256: true`, `reconciliation.balanced: true`, `csvContainsBothPromotionOrders: true`가 자동 판정이다. `taxInvoiceStatus: SIMULATED`는 MockTaxInvoiceIssuer 결과이며 실제 세금계산서 발행을 뜻하지 않는다.

데모는 공개 게이트웨이 API와 인증된 시뮬레이터 제어 API만 사용한다. Tempo·actuator는 상태 진단에만 사용한다. 데이터베이스 수정, Kafka 수동 발행, 내부 견적 API 호출은 이 데모 태스크에 없다. 기존 `:e2e:e2eTest`의 확장 릴리스 운영 테스트는 catalog 내부 비상 API를 검증하므로 데모 태스크에서 제외하고 전체 E2E에는 남겨뒀다.

## 다시 실행하고 결과 검토하기

두 회차는 각각 새 계정, 상품코드, 업로드 객체, 주문번호, 행사 ID를 만들며 볼륨을 비울 필요가 없다. 월은 정산 서비스의 실제 귀속 월을 사용하고 판매자·주문 식별자로 회차를 구분한다. 실패한 회차도 일부 데이터가 남을 수 있으므로 `run`을 다시 실행하면 새 키 공간에서 처음부터 시작한다. 새 환경으로 완전히 초기화하려면 스택을 내린 뒤 해당 Compose 볼륨을 운영자가 명시적으로 제거해야 한다. 이 스크립트는 볼륨을 지우지 않는다.

`summary.md`와 두 JSON, `service-tests.log`, `stack-gate.log`가 자동 판정 증거다. 수동 확인은 이 파일들의 식별자·금액과 실패 시 해당 API·서비스 로그를 보는 것이다. 상점 웹 화면, 브라우저 E2E, 포트원 테스트 채널은 이 검증 범위에 포함되지 않는다.

## 빈 볼륨 실행 기록 (2026-09-30)

기존 스택을 볼륨 삭제 없이 내리고 별도 이름의 빈 MySQL·MongoDB·MinIO·Tempo 볼륨으로 기동했다. 소스 `7873593`에서 스택 게이트 16/16, 캐시 없이 실행한 서비스 단위·통합·OpenAPI 계약 테스트, API 여정 57/57 두 회차가 모두 통과했다. 두 회차 모두 실제 ZIP 다운로드 SHA-256 일치, 판매자·플랫폼 할인 환불 역분개, 월 대사 균형과 CSV 내역 포함을 확인했다.

| 회차 | 상품코드 | gameId | buildId | releaseId |
|---|---|---:|---:|---:|
| 1 | `GAME-E2E-1790711775663-91bd35` | 1 | 1 | 4 |
| 2 | `GAME-E2E-1790711847465-a5b9af` | 2 | 4 | 8 |

로컬 원본 로그와 비밀값을 제외한 JSON은 Git 제외 경로 `build/api-demo/issue81-fresh-20260929T194605Z/`에 있다. 검증 뒤 원래 볼륨으로 스택을 복구했고 상태 게이트 16/16을 다시 통과했다. 새 환경 검증 첫 시도에서는 1초 백오프 경계에 의존하던 서비스 통합 테스트가 시간 경과로 실패하여, SQL의 미래 시각 필터 테스트를 안정화한 뒤 **다른 새 볼륨**에서 위 결과를 얻었다.
