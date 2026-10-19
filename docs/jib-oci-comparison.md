# Dockerfile과 Jib의 OCI 비교

## 대상과 통제 조건

- 기준 코드: `2ccad87cc39e7ec655fc58f12aec4cf36ac3a335`의 수정 없는 Git 스냅샷.
- 대상: 재작성 완료 실행 모듈 11개와 독립 공급자 `simulators/mock-pg` 1개. 공급자는 업무 모듈 수에 포함하지 않는다.
- 기존 방식: Gradle `bootJar` → 기존 Dockerfile → 레지스트리 게시.
- 비교 방식: Jib Gradle 플러그인 3.5.4의 exploded 방식 → 레지스트리 직접 게시.
- 환경: 기존 OCI Ampere A1 ARM64 4코어, 약 24GB RAM, Docker 28.1.1.
- 두 방식의 Java 기반 이미지를 동일 다이제스트로 고정한다. 이미지 JVM은 Temurin 21.0.12.1+1이며, 컴파일 JDK는 OCI의 Temurin 21.0.12+8이다.
- UID/GID `100:101`, `JAVA_OPTS`, 시간대, 종료 유예와 HTTP healthcheck를 유지한다. Jib의 healthcheck는 Compose 오버라이드로 제공한다.
- 기준 작업본과 Jib 작업본, 루프백 전용 레지스트리, 검증용 DB·Kafka·MinIO·Redis를 별도로 만든다. 기존 공유 컨테이너·볼륨·측정 자료를 덮어쓰지 않는다.

현재 사용자가 OCI 배포·측정을 요청했으므로 이 실험의 빌드·배포·런타임 측정은 OCI에서 한다. 로컬에서는 편집·정적 검증·단위 테스트를 수행한다.

## 빌드 측정

의존성과 빌드 도구 다운로드를 준비 단계에서 완료한 후 측정한다. 테스트는 이미지 게시 시간과 분리해 동일 검증 범위를 유지한다.

| 조건 | 범위 | 반복 | 캐시 조건 |
|---|---|---:|---|
| clean-output | 실행 모듈 12개 | 방식별 3회 | Gradle clean, 의존성·컴파일 빌드 캐시·기반 이미지 캐시 유지. Docker는 `--no-cache`, Jib은 clean으로 프로젝트 이미지 캐시 제거 |
| no-change | 실행 모듈 12개 | 방식별 3회 | 직전 빌드 결과·이미지 캐시 유지 |
| class-change | Store 하나 | 방식별 3회 | 격리 작업본에 실행 경로에서 사용하지 않는 클래스 하나를 추가·변경 |

Docker 이미지 빌드·게시는 최대 4개를 병렬 실행하고, Jib도 기존 Gradle 최대 워커 4개를 사용한다. `--no-daemon`을 양쪽에 동일하게 적용한다. 기준 방식에만 테스트를 포함하거나 Jib 쪽 테스트를 생략해 속도 개선으로 계산하지 않는다.

수집 항목은 전체 이미지 게시 시간, 단계별 실행 시간, 호스트 busy CPU 시간, 이미지 레이어 수·압축 크기·다이제스트, 직전 이미지 대비 변경된 레이어의 압축 바이트다. 변경 바이트는 레지스트리 manifest에 기록된 blob 크기로 계산한다. HTTP/TLS 헤더·재전송을 포함한 실제 인터넷 회선 전송량과 구분한다. 이 실험의 레지스트리는 OCI 호스트 루프백에 있으므로 인터넷 업로드 성능으로 해석하지 않는다.

빌드 CPU 비교는 각 회차 최상위 `host_busy_cpu_seconds`만 사용한다(`/proc/stat`의 total−idle−iowait). 병렬 작업의 단계별 벽시계 시간·호스트 CPU와 제어 프로세스의 자식 CPU 누적 차이는 서로 중첩되므로 합산하지 않는다. Docker CLI 프로세스의 CPU만으로 Docker 데몬의 비용을 대신하지 않는다.

clean-output은 소스 출력과 이미지 생성 캐시를 비운 조건이며, 의존성까지 없는 신규 CI 머신의 cold build가 아니다. 저장소의 `org.gradle.caching=true`를 유지해 컴파일은 FROM-CACHE 복원을 허용한다. 반복 간 레지스트리 blob은 보존한다. no-change에서 기존 Dockerfile도 캐시를 사용한다. Jib 첫 회차는 전용 기반 이미지 캐시를 처음 채우므로 이후 회차보다 느리며, 이 값을 제외하지 않고 범위와 중앙값에 포함한다.

## 배포와 실행 측정

방식별로 빈 검증용 데이터 저장소를 사용한다. 12개 앱에 동일하게 메모리 상한 512MiB를 적용하고, 외부 추적기 부하를 제외하기 위해 tracing sample rate를 양쪽 모두 0으로 둔다.

1. 모든 앱의 공개 `/actuator/health` 응답과 전체 기동 시간을 수집한다. `mock-pg·auth·gateway` → `studio·review·catalog` → `store·order·payment` → `license·download·settlement` 순서로 최대 세 JVM씩 기동한다. Store는 Catalog가 준비된 다음 시작한다.
2. 기존 Settlement E2E의 `initialPurchasesAndDownloads`와 `allocationAndOwnershipAreIsolated`를 그대로 실행한다. 실제 두 상품 출시, 세 주문 결제, License 보유권, 원본 ZIP 다운로드와 판매자 배분·권한 분리를 확인한다.
3. 빌드 JVM을 종료하고 Store 목록·제목 검색·상세를 Gateway 경유로 조회한다. 응답 코드뿐 아니라 `success`와 비어 있지 않은 상품 데이터도 확인한다.
4. 20·60 RPS 사전 확인 후 20 RPS로 20초 준비, 60초 본 측정한다. 요청별 지연·스케줄 지연·오류와 Docker cgroup CPU·working set 메모리를 저장한다.
5. 수집 가능한 Prometheus 원문과 GC 누적값, 앱 로그를 보존한다. 기존 보안 정책에서 Auth·Gateway 외 업무 앱은 Prometheus 요청을 403으로 거부한다. 거부·미제공 응답을 0으로 바꾸지 않고 오류로 기록하며, 전체 앱의 CPU·메모리는 Docker cgroup 통계로 수집한다. GC 누적값은 기동·기능 검증을 포함하므로 60초 부하 구간의 GC 횟수로 오인하지 않는다.

부하 생성기는 OCI 호스트에서 실행되므로 외부 네트워크 지연을 포함하지 않는다. 같은 초기 데이터·부하와 최소 2회 반복으로 비교하고, 편차 범위를 함께 본다. 기준/Jib의 런타임 차이가 작으면 속도 향상으로 단정하지 않는다.

## 실행과 원자료

실행 도구: `scripts/image-benchmark.py`, `scripts/image-benchmark/worker.py`, `scripts/image-benchmark/runtime.py`.

```bash
python3 scripts/image-benchmark.py inventory
python3 scripts/image-benchmark.py prepare
python3 scripts/image-benchmark.py baseline
python3 scripts/image-benchmark.py overlay
python3 scripts/image-benchmark.py runtime-baseline
python3 scripts/image-benchmark.py jib
python3 scripts/image-benchmark.py runtime-jib
python3 scripts/image-benchmark.py runtime-baseline
python3 scripts/image-benchmark.py deploy-jib
python3 scripts/image-benchmark.py verify-deployment
python3 scripts/image-benchmark.py collect
```

세션 ID: `20261019T125630Z`. OCI의 전용 세션 디렉터리와 로컬 Git 제외 경로 `runs/image-benchmark/20261019T125630Z/`에 JSON·로그·요청별 원자료를 보존한다. 생성 Compose 파일에는 검증용 키가 있으므로 파일 권한은 600으로 제한한다.

요약을 다시 계산하려면 다음을 실행한다. 출력 JSON은 성공한 회차만 집계하고, 빌드 조건별 3회·실행 방식별 2회가 모였는지 `complete`로 표시한다.

```bash
python3 scripts/image-benchmark/report.py runs/image-benchmark/20261019T125630Z
```

마지막 `deploy-jib`는 기능·부하 검증을 마친 Jib 스택을 남긴다. 같은 세션을 종료할 때는 `python3 scripts/image-benchmark.py cleanup`을 사용한다. 이 명령은 세션의 Compose 프로젝트와 소유 라벨이 일치하는 레지스트리만 정리하며 소스·측정 자료·다른 프로젝트의 데이터는 보존한다. 다른 측정을 시작하기 전에는 기존 실험 세션의 포트를 해제한다.

## 일반 실행에서 Jib 사용

기존 Dockerfile 경로와 별도로 사용할 수 있게 추가했다. 실행 모듈만 Jib을 적용하며 공통 라이브러리·E2E에는 적용하지 않는다.

```bash
# 로컬 Docker 데몬에 이미지 생성
./gradlew jibDockerBuild

# 레지스트리에 직접 게시: registry.example.com은 실제 대상에 맞춰 바꾼다.
./gradlew jib -Pstove.jib.registry=registry.example.com/stove \
  -Pstove.jib.tag=release-id -Pstove.jib.platform=arm64

# 기존 인프라·서비스 키가 준비된 환경에서, 검증한 기동 순서로 실행한다.
export STOVE_JIB_REGISTRY=registry.example.com/stove STOVE_IMAGE_TAG=release-id
compose=(docker compose -f docker-compose.apps.yml -f docker-compose.apps.ci.yml \
  -f docker-compose.apps.e2e.yml -f docker-compose.apps.jib.yml)
"${compose[@]}" up -d --no-build --wait --wait-timeout 180 mock-pg auth gateway
"${compose[@]}" up -d --no-build --wait --wait-timeout 180 studio review catalog
"${compose[@]}" up -d --no-build --wait --wait-timeout 180 store order payment
"${compose[@]}" up -d --no-build --wait --wait-timeout 180 license download settlement
```

레지스트리 인증은 Jib의 Docker credential helper 또는 CI 자격증명 설정을 사용한다. `stove.jib.baseImage`로 기반 이미지의 digest를 지정할 수 있다. `stove.jib.insecure=true`는 이 실험의 루프백 HTTP 레지스트리에만 사용했다.

## 측정 결과

빌드·게시 구간은 각 조건을 3회 측정했다. 아래는 중앙값이며 괄호는 최솟값~최댓값이다. 테스트·도구 다운로드·검증용 인프라 준비 시간은 포함하지 않는다.

| 조건 | 기존 Dockerfile | Jib | 중앙값 감소 |
|---|---:|---:|---:|
| 출력 삭제 후 12개 이미지 생성·게시 | 91.68초 (89.38~93.36) | 31.75초 (31.13~47.96) | **65.4%** |
| 변경 없는 12개 이미지 재게시 | 21.73초 (21.44~21.82) | 19.47초 (19.21~20.21) | **10.4%** |
| Store 클래스 하나 추가·변경 후 게시 | 20.56초 (19.86~22.25) | 14.41초 (14.15~14.69) | **29.9%** |
| 출력 삭제 조건의 호스트 busy CPU 합계 | 195.67 CPU초 | 97.70 CPU초 | 50.1% |

이미지 크기는 registry manifest의 **gzip 압축 레이어 바이트**이며 `docker images`의 비압축 Size와 다르다. 대상 12개는 기준 코드의 공개본이며 실험용 변경 클래스가 들어간 태그를 사용하지 않는다.

| 이미지 지표 | 기존 Dockerfile | Jib | 해석 |
|---|---:|---:|---|
| Store 이미지 레이어 합계 | 231.70MiB | 150.63MiB | 약 35.0% 감소 |
| 12개 이미지 레이어의 논리적 합계 | 2,613.04MiB | 1,723.87MiB | 34.0% 감소, 공통 기반 레이어 중복 포함 |
| 12개 공개본에 필요한 고유 blob 합계 | 1,842.92MiB | 532.68MiB | **71.1% 감소**, 이미지 사이 중복 digest 제거 |
| Store 클래스 변경 시 변경 레이어 | 161.69MiB | 17.90KiB | 기존 의존성을 다시 담는 양 감소. 실제 회선 계측값은 아님 |
| 무변경 Store 재게시 3회의 digest 종류 | 1개 | 1개 | 양쪽 모두 캐시 상태에서 이미지가 안정적 |

기존 Store 이미지에는 각각 약 80.84MiB인 JAR 복사·소유권 변경 레이어가 있다. Jib의 크기 감소에는 이 중복을 없애는 효과와 의존성·프로젝트 라이브러리·클래스의 분리, 이미지 사이 레이어 공유가 함께 작용한다. 기존 Dockerfile에도 [COPY --chown](https://docs.docker.com/reference/dockerfile/#copy---chown)과 레이어 분리를 적용할 수 있으므로 이 수치를 모든 Dockerfile 대비 Jib의 고유 우위로 일반화하지 않는다.

동일 소스로 clean-output을 수행한 Store 이미지 digest는 **양쪽 모두 3종류**였다. 이 실험에서 출력 삭제 후 빌드의 재현성까지 확인됐다고 주장할 수 없다. Jib의 EPOCH 설정만으로 빌드 도구가 생성하는 JAR 내부 바이트까지 같아지는 것은 아니다.

실행 순서는 기준 A1 → Jib B1 → 기준 A2 → Jib B2다. 회차마다 빈 DB·Kafka·Redis·MinIO에서 같은 기능 검증과 부하를 실행했다. 아래 지연은 **회차별 percentile의 중앙값**이며 괄호는 두 회차의 범위다.

| 실행 지표 | 기존 Dockerfile | Jib | 해석 |
|---|---:|---:|---|
| 12개 앱의 HTTP health 준비 완료 | 110.60초 (110.53~110.67) | 94.80초 (92.12~97.48) | 이 조건에서 **14.3% 단축** 관측 |
| 상품 조회 p50 | 7.40ms (7.38~7.42) | 7.39ms (7.38~7.40) | 범위 겹침 |
| 상품 조회 p95 | 9.02ms (8.90~9.14) | 8.89ms (8.83~8.96) | 범위 겹침, 확실한 개선 판정 안 함 |
| 상품 조회 p99 | 16.49ms (16.30~16.69) | 11.21ms (10.41~12.00) | 감소 관측. 원인·다른 부하에서의 재현성은 미확인 |
| 60초간 앱 12개의 CPU 사용 합계 | 25.40 CPU초 (25.00~25.79) | 24.69 CPU초 (24.28~25.10) | 범위 겹침 |
| 앱 12개의 평균 working set 합계 | 3,956.81MiB (3,867.58~4,046.03) | 3,869.17MiB (3,861.22~3,877.12) | 범위 겹침, 메모리 절감 확정 안 함 |
| 본 측정 요청·실패 | 2,400건·0건 | 2,400건·0건 | 모두 목표 20 RPS 충족 |

기동 시간은 이미지 pull·인프라 준비가 끝난 뒤 첫 앱 기동부터 마지막 앱의 health 성공까지다. 빈 업무 DB의 초기 마이그레이션과 세 JVM씩 기동하는 순서를 포함한다. p99 감소는 이 작은 표본에서 관측한 결과로 남기며, 전체 앱의 GC·스케줄링 원인을 특정하거나 최대 처리량 향상으로 해석하지 않는다. 운영체제 페이지 캐시는 비우지 않았다.

빌드 시간 감소를 전체 CI 시간 감소로 환산하지 않는다. 현재 저장소에서 가장 분명하게 확인된 효과는 **이미지 생성·게시 시간과 변경 레이어·이미지 사이 중복 blob의 감소**다. 기동 시간도 이번 조건에서 줄었지만, p50·p95·CPU·메모리의 범위는 겹쳤다.

기계 판독용 결과는 [jib-oci-results.json](jib-oci-results.json), 회차별 JSON·요청 1건 단위 지연·2초 간격 cgroup 샘플·원문 로그는 위 세션의 Git 제외 원자료에 있다. `source.json`에 기준 Git 아카이브와 Jib 설정 세 파일의 SHA-256을 보존했다.

## 현재 OCI 배포

- Jib 애플리케이션 컨테이너 **12개가 모두 healthy**, 재시작 0회다.
- 실행 중인 각 컨테이너의 이미지 ID를 실제 registry manifest의 config digest와 대조했다. UID/GID `100:101`과 루프백 전용 포트 바인딩도 확인했다.
- 전용 Compose 프로젝트: `stove-imgbench-20261019t125630z-runtime`.
- Gateway: OCI 루프백 `127.0.0.1:48080`, 서명 ZIP URL용 MinIO: `127.0.0.1:43841`.
- 실행 정의와 확인 결과는 원자료의 `deployment.json`, `deployment-verification.json`에 있다. 비밀을 포함하는 Compose 파일은 전용 OCI 세션의 `results/jib-runtime-2/compose.json`에 권한 600으로 저장했다.

로컬에서 API와 서명 URL에 접속하려면 기존 SSH 별칭을 사용해 두 포트를 전달한다.

```bash
ssh -N -L 48080:127.0.0.1:48080 -L 43841:127.0.0.1:43841 \
  "$(git config --get stove.remote)"
```

접속 주소는 `http://127.0.0.1:48080`이다. 데이터는 실제 업무 흐름 검증에서 생성한 격리된 테스트 계정·상품·모의 결제·정산 자료다.

## 검증과 제외 회차

- 로컬 `./gradlew test --no-daemon --console=plain`: 현재 settings.gradle에 포함된 모듈의 JUnit XML 기준 **1,014건, 실패·오류·건너뜀 0**. 제거된 모듈과 별도 작업 트리의 오래된 XML은 집계하지 않았다.
- 로컬 `:apps:store:jibBuildTar`: Docker 데몬이 없는 상태에서 성공. 레이어 10개와 실제 Store main class·classpath 파일을 확인했다.
- `docker-compose.apps.jib.yml`을 합친 Compose 설정 검증 통과.
- `verifyCommonRuntimeDependencies` 통과. 실행 클래스패스의 공통 모듈 의존성 경계를 확인했다.
- 초기 예비 회차는 일부 모듈의 인증이 필요한 `/health/readiness` 사용과 12개 JVM 동시 기동 때문에 중단했다. 동시 기동 로그에서 Order DB 연결 시간 초과와 Catalog 준비 전 Store 초기 동기화 실패를 확인했다. 애플리케이션 코드를 바꾸지 않고, 양쪽에 같은 공개 health 경로와 선행 기동 순서를 적용한 뒤 재측정했다. 예비 회차는 요약 JSON이 없으며 `*-failed-*` 폴더에 보존하고 집계에서 제외한다.
