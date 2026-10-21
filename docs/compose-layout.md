# Compose 실행 구성

작성일·완료일: 2026-10-21. 상태: 완료.

[개발 환경과 실행](getting-started.md) · [Dockerfile 제거 기록](dockerfile-removal-plan.md)

## 파일과 역할

루트 Compose 파일을 7개에서 4개로 줄였다. 기본 파일은 호스트 포트를 열지 않고, 로컬 오버라이드가 호스트 접속을 담당한다. Jib 이미지·환경 설정·healthcheck·종료 유예는 기본 파일에서 관리한다.

| 파일 | 역할 |
|---|---|
| `docker-compose.yml` | 인프라 서비스·네트워크·볼륨. 호스트 포트 없음 |
| `docker-compose.apps.yml` | 업무 앱 11개·외부 모의 PG·전용 PG DB. 호스트 포트 없음 |
| `docker-compose.local.yml` | 인프라의 `127.0.0.1` 포트 바인딩 |
| `docker-compose.apps.local.yml` | 앱의 `127.0.0.1` 포트와 브라우저용 OAuth·업로드 주소 |

기존 CI 오버라이드 2개는 기본 파일의 포트를 다시 지우는 역할이었다. 포트를 기본 파일에서 제거해 이 오버라이드들을 삭제했다. 기존 E2E 오버라이드 2개는 local 파일에 통합했다. `!reset`을 적용한 뒤 포트를 다시 추가하던 순서 의존성도 제거했다.

로컬 실행과 호스트 JVM의 E2E는 같은 local 오버라이드를 사용한다. `local-stack.sh`의 `up`과 `demo-up`도 같은 구성이다. `demo-up`은 추가로 전체 스택 준비 상태를 기다린다. 원격 검증 스크립트도 이 구성을 사용하며 모든 게시 포트는 원격 호스트의 루프백에 바인딩된다.

## 실행

신규 스택의 키·DB 설정은 [시작 안내](getting-started.md#로컬-스택과-swagger)를 먼저 따른다.

```bash
# 이미지 생성과 로컬 인프라·앱 시작
./scripts/local-stack.sh up
./scripts/local-stack.sh status

# 컨테이너 종료. 데이터 볼륨은 보존한다.
./scripts/local-stack.sh down
```

직접 실행할 때 적용할 파일은 각 스택당 두 개다.

```bash
./gradlew jibDockerBuild
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d
docker compose -f docker-compose.yml -f docker-compose.local.yml wait mysql-bootstrap
bash scripts/start-apps.sh -f docker-compose.apps.yml -f docker-compose.apps.local.yml
```

컨테이너 네트워크 안에서만 사용하는 CI 실행은 각 기본 파일만 적용한다. 호스트에서 실행하는 E2E는 local 파일을 추가한다. 기존 프로젝트 이름 `stove`·`stove-apps`, 컨테이너 이름, 네트워크·볼륨 정의는 유지했다. 기존 스택의 포트 설정은 다음 컨테이너 재생성 시 반영된다.

## 로컬 접속 주소

모든 호스트는 `127.0.0.1`이다. 앱의 호스트 포트는 내부 포트에 10000을 더한다.

| 용도 | 포트 |
|---|---|
| Gateway·Swagger·브라우저 로그인 | `18080` |
| Auth 직접 조회 | `18091` |
| Catalog·Order·Payment·License·Studio·Review·Store·Download·Settlement | `18081`~`18089` |
| MinIO S3 API / 콘솔 | `19000` / `9001` |
| Tempo 조회 / OTLP HTTP 수집 | `13200` / `4318` |
| MySQL / Redis / Kafka | `3306` / `6379` / `9092` |
| Kafka UI / exporter | `8090` / `9308` |
| Prometheus / Grafana | `9090` / `3000` |
| 보존 중인 Elasticsearch / MongoDB | `9200` / `27017` |

MinIO의 중복 S3 포트 `9000`은 제거하고 서명 업로드·다운로드 주소와 같은 `19000`만 사용한다. Tempo 조회는 E2E와 같은 `13200`으로 통일했다. 외부 모의 PG와 전용 PG DB는 local 구성에서도 호스트 포트를 열지 않는다.

## 검증 기록

- 변경 전후 `docker compose config --format json`을 비교했다. 기본 인프라·앱은 각각 기존 기본+CI 구성과 동일하다.
- 새 앱 local 구성은 기존 기본+CI+E2E 구성과 동일하다. 인프라 local 구성은 포트 외 모든 설정이 기존 로컬 구성과 동일하다.
- 기본 구성의 게시 포트는 0개다. 로컬 인프라는 13개, 앱은 11개이며 모두 `127.0.0.1`이다.
- 배포 계약 테스트 5건에서 실패·오류·건너뜀은 0건이다. 기본 파일의 무포트 구성과 local 파일의 루프백 바인딩을 회귀 검사에 추가하고 4개 파일을 Gradle 테스트 입력으로 등록했다.
- 로컬·원격·성능 측정 스크립트의 파일 참조를 갱신하고 Bash 문법 검사를 통과했다. 과거 결함·설계·이미지 비교 기록의 파일명은 당시 기록으로 보존한다.
- `python3 tools/verification/verify-jib.py --skip-build`가 로컬 OrbStack에서 성공했다. 기존 Jib 이미지 12개의 실제 기동·healthy·UID/GID·JVM 설정과 정상 종료를 확인했다. 재시작은 0회이며 종료 12건 모두 SIGTERM에 따른 코드 143과 graceful 완료 로그를 확인했다.
- 실제 E2E 3건에서 실패·오류·건너뜀은 0건이다. 상품 2개 출시·주문 3개 결제·License 지급·ZIP 다운로드, 판매자 배분·권한 분리, 독립 PG 지급 명세·대사·수령 재요청의 멱등성을 확인했다.
- 실행 검증은 기본 앱의 이미지·환경 설정·healthcheck·종료 유예를 가져와 별도 프로젝트·포트·DB에서 수행했다. MySQL·PG MySQL·Redis·Kafka·MinIO를 사용했고 검증용 컨테이너·네트워크는 정리했다. 관측 도구 등 나머지 인프라는 구성 비교로 확인했으며 원격 배포는 실행하지 않았다.

구성 비교와 배포 계약 테스트의 원자료는 `runs/compose-simplification-gd1p5pyv/`, 실제 실행 원자료는 `runs/jib-validation-xay4ck0i/`에 보관한다. 후자의 `result.json`은 `complete=true`, 앱 12개·정상 종료 12개를 기록한다. 이번 변경에서는 Compose 설정을 검사하는 테스트와 실제 배포 여정을 실행했다. 앞선 Dockerfile 제거 작업의 전체 단위·통합 테스트 결과는 [별도 기록](dockerfile-removal-plan.md#최종-검증-결과)을 따른다.
