# G5 배포 시 외부 비밀 주입

2026-11-05 사용자의 후속 지시로 서버 보호 파일 방식에서 배포 환경 변수 직접 주입 방식으로 변경했다. 운영 비밀 파일의 영구 경로는 필요하지 않다.

## 흐름과 책임

배포 도구의 비밀 설정 → 배포 프로세스 환경 → Docker Compose → 필요한 앱 환경.

실행기는 비밀 파일을 생성하거나 읽지 않는다. 배포 도구는 비밀 값을 환경 변수로 주입하며 shell source나 명령 인자에 값을 넣지 않는다. 특정 CI 공급자나 외부 비밀 저장소는 아직 선택하지 않았다. 기존 운영 키를 새로 만들거나 교체하지 않는다.

기존 이름을 유지한 scripts/protected-compose.py가 새 진입점이다. --env-file 인자는 제거했다. Compose에는 --env-file /dev/null을 지정해 작업 디렉터리의 .env 자동 읽기를 막고 COMPOSE_* 상속 설정을 거절한다.

## 실행

배포 도구가 필수 환경 변수를 주입한 상태에서 다음 명령을 실행한다.

```sh
# 누락과 Compose 구문만 검사한다. 컨테이너를 시작하지 않는다.
python3 -B scripts/protected-compose.py --project-name deployment-apps

# 실제 배포 작업에서만 실행한다.
python3 -B scripts/protected-compose.py --project-name deployment-apps --apply
```

프로젝트 이름은 예시다. --apply는 기존 start-apps.sh의 순차 시작·health 대기를 사용한다. 사전 검사가 실패하면 시작하지 않는다. 시작 도중 실패하면 일부 앱이 이미 시작됐을 수 있으며 자동 롤백은 하지 않는다. 기존 인프라와 stove_default 네트워크가 준비되어야 한다. 프로젝트 이름만으로 네트워크·DB까지 격리되지는 않는다.

## 필수 입력

- 공통 DB_PASSWORD, Studio STORAGE_ACCESS_KEY / STORAGE_SECRET_KEY.
- BFF_CLIENT_SECRET, AUTH_ADMIN_PASSWORD / AUTH_REVIEWER_PASSWORD.
- CATALOG_STORE_SERVICE_KEY / CATALOG_ORDER_SERVICE_KEY / CATALOG_DOWNLOAD_SERVICE_KEY / CATALOG_SETTLEMENT_SERVICE_KEY.
- PAYMENT_ORDER_SERVICE_KEY, PAYMENT_APPROVAL_PAYLOAD_KEY.
- MOCK_PG_PAYMENT_KEY / MOCK_PG_SETTLEMENT_KEY / MOCK_PG_CONTROL_KEY.
- MOCK_PG_DB_PASSWORD / MOCK_PG_DB_ROOT_PASSWORD.
- REVIEW_STORAGE_ACCESS_KEY / REVIEW_STORAGE_SECRET_KEY.
- DOWNLOAD_STORAGE_ACCESS_KEY / DOWNLOAD_STORAGE_SECRET_KEY.
- CATALOG_DOWNLOAD_STORAGE_ENABLED=true라면 CATALOG_DOWNLOAD_STORAGE_ACCESS_KEY / CATALOG_DOWNLOAD_STORAGE_SECRET_KEY도 필요하다.

빈 값과 누락은 시작 전에 거절한다. 키 형식·강도·외부 인증 성공을 모두 보장하는 검사는 아니다. Payment 암호화 키 형식은 앱의 기존 검증을 따른다. docker-compose.injected.yml은 기존 앱 Compose 위에 DB와 Studio 저장소 설정을 덮어써 개발 기본 비밀로의 복귀를 막는다. 각 서비스의 키 수신 범위는 기존 명시적 environment 정의를 따른다.

DB·MinIO 등 인프라 계정은 별도로 준비한 동일한 자격증명과 일치해야 한다. 이 실행기는 기존 DB 비밀번호를 변경하거나 인프라를 배포하지 않는다. 기본 개발 Compose와 local-stack.sh의 개발 동작도 유지한다. PortOne 제공사 전환과 실제 금융 동작은 이번 배포 경로에 추가하지 않았다.

## 노출과 교체

실행기의 Compose stdout/stderr는 성공·실패 모두 숨긴다. 테스트와 보고서는 알려진 픽스처만 사용한다. 배포 도구의 명령 추적(set -x), 환경 덤프, Compose config 출력도 금지해야 한다. Docker 관리자와 실행 계정은 프로세스·컨테이너 환경의 키를 읽을 수 있다.

서비스 접근키는 현재 한 값만 받는다. 한쪽만 교체하면 호출이 실패할 수 있다. Payment 승인 암호화 키는 기존 자료 복호화에 필요하므로 임의 재생성하지 않는다. 무중단 교체·폐기 정책은 별도다.

## 검증과 남은 범위

```sh
python3 -B -m unittest discover -s tools/verification -p test_protected_compose.py -v
```

필수 입력별 누락·빈 값·COMPOSE 제어 거절·파일 자동 읽기 차단·출력 억제·검사 실패 시 시작 금지·명시적 apply의 기존 실행기 연결을 검사한다. 실제 Docker Compose 병합에서는 알려진 픽스처의 달러/공백 값 보존, DB/Studio 비밀 대체와 서비스 키 수신자 경계를 확인한다. 실제 앱 시작은 모의 호출이며 런타임 배포 성공과 구분한다.

실제 배포 도구의 비밀 등록·운영 배포·키 교체는 수행하지 않았다. 기존 비밀 파일과 공유 컨테이너를 변경하지 않았다. G5 전체는 미완료다.
