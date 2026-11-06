# G5 배포·검증 실행기의 키 전달 범위

2026-11-05 기본/로컬 Compose와 현재 Gateway 소유권 검증 실행기의 프로젝트 비밀 전달 범위를 점검했다. 앞선 HTTP 접근키 검사와 별개로, 어떤 프로세스에 키를 넣는지 검증한 G5 하위 작업이다. 실제 운영 배포나 비밀 저장소 구축을 완료했다는 뜻은 아니다.

## 발견과 수정

1. 기본 Compose에 Payment→Order의 PAYMENT_ORDER_SERVICE_KEY 전달이 없었다. Order·Payment 양쪽 환경에 같은 외부 설정 참조를 추가했다. Payment의 승인 접수 암호화용 PAYMENT_APPROVAL_PAYLOAD_KEY도 Payment에만 전달하도록 선언했다. 기본 빈 값과 SIMULATED 제공사 모드는 유지하며 PortOne 금융 동작을 켜지 않았다.
2. 실제 서비스 소유권 검증 실행기는 공통 환경에 Catalog 키·BFF 비밀·Auth bootstrap 비밀번호·저장소 키 등을 넣고 모든 앱에 전달하고 있었다. `support/service_credentials.py`에서 앱별 필요한 프로젝트 비밀만 선택하도록 바꿨다. Gateway·Download에는 DB_PASSWORD를 주지 않고, 모의 PG 키는 기존 MockPgRuntime이 Payment/Settlement용으로 각각 주입한다.
3. Catalog는 다운로드 금지 검증용 별도 읽기 클라이언트를 사용하므로 Download 읽기 계정도 필요하다. 이를 불필요한 키로 지우지 않았다. 기존 YAML·DownloadStorageConfiguration의 요구와 테스트로 보존했다.
4. 실행 중 Order가 test-fixtures JAR을 골라 시작하지 못하는 문제가 드러났다. 실행 JAR 선택에서 plain·test-fixtures를 제외하고 후보가 정확히 하나인지 확인하도록 고쳤다. 누락·여러 버전도 명시적으로 실패한다.

## 서비스 접근키 계약

| 외부 설정 | 기본 Compose의 수신 서비스 |
|---|---|
| CATALOG_STORE_SERVICE_KEY | Catalog·Store |
| CATALOG_ORDER_SERVICE_KEY | Catalog·Order |
| CATALOG_DOWNLOAD_SERVICE_KEY | Catalog·Download |
| CATALOG_SETTLEMENT_SERVICE_KEY | Catalog·Settlement |
| PAYMENT_ORDER_SERVICE_KEY | Payment·Order |
| PAYMENT_APPROVAL_PAYLOAD_KEY | Payment |
| BFF_CLIENT_SECRET | Auth·Gateway |
| MOCK_PG_PAYMENT_KEY | 외부 모의 PG·Payment |
| MOCK_PG_SETTLEMENT_KEY | 외부 모의 PG·Settlement |
| MOCK_PG_CONTROL_KEY | 외부 모의 PG |
| DOWNLOAD_STORAGE_SECRET_KEY | Catalog·Download |

배포 계약은 환경 변수 이름뿐 아니라 같은 이름의 외부 참조를 쓰는지도 확인한다. 로컬 오버라이드가 비밀 전달 범위를 넓히지 않아야 한다. Python 검사는 `docker compose --env-file /dev/null ... config --format json`에 알려진 테스트 값만 주어 실제 Compose 병합 결과를 확인하며, 기존 .env 파일을 읽거나 실제 비밀값을 출력하지 않는다.

검증 실행기의 선별은 알려진 프로젝트 접두사와 KEY/SECRET/PASSWORD 이름, DB_PASSWORD를 대상으로 한다. PATH·JAVA_HOME·포트·주소와 프록시 등 일반 런타임 설정을 유지하고 입력 환경 객체를 변경하지 않는다. 새 프로젝트 키는 별도 허용하지 않으면 전달되지 않는다. 알 수 없는 앱 이름은 실패한다. 모든 가능한 비밀 이름·외부 프로세스·기존 검증 도구 전체를 자동 검사하는 범용 보안 도구는 아니다.

## 검증 결과

- 기존 공통 환경을 그대로 반환하는 방식은 환경 선택 계약 7건을 실패시켰다. 미구현 import 오류와 구분한 행동 Red다. 앱별 선별 뒤 최종 9건이 통과했다. 여기에는 실제 Compose 병합 및 값 보존, 키 이름만 남기는 기록, Catalog 읽기 계정 보존이 포함된다.
- 기본 Compose 배포 계약은 PAYMENT_ORDER_SERVICE_KEY의 수신자가 빈 집합이라 실패했다. 수정 후 새 계약 2건과 기존 공통 검사를 포함한 `:common:test:test` 전체 7건이 통과했다.
- 실행 JAR 회귀 검사 3건이 통과했다. 테스트 픽스처 JAR이 있어도 실행 JAR을 고르고 후보가 없거나 두 개 이상이면 거절한다.
- 실제 11개 앱과 독립 모의 PG·전용 MySQL/Redis/Kafka/MinIO 실행에서 로그인·본인/타인 조회·역할 거절의 기존 관측 91개가 통과했다. 구매 자료는 기존과 같은 전용 DB 픽스처이며 실제 구매·금융 동작의 검증으로 확대하지 않는다.
- 성공 실행 `stove-ownership-check-uq_2z3iw`의 credential-recipients.json에는 앱에 전달한 프로젝트 비밀의 이름만 저장했다. 이 목록으로 Catalog 네 관계·Payment→Order·BFF·Auth 비밀번호·모의 PG 키의 11개 수신자 계약을 대조했다. Gateway·Download의 DB_PASSWORD 부재도 확인했다. 키 값은 기록하지 않았다.
- 최초 재실행 `stove-ownership-check-5vpnpj6r`는 Order 테스트 픽스처 JAR 선택 때문에 중단했다. 수정 후 성공 실행은 ownership-results.json의 success=true, 종료 코드 0을 확인했다. 두 실행 모두 전용 자원을 종료했고 기존 컨테이너 23개·상태·볼륨 57개의 before/after가 일치했다.

Python 검사 12건·Java 공통 검사 7건과 HTTP 관측 91개는 종류가 다르므로 하나의 테스트 수로 합산하지 않는다. 이전 HTTP 접근키 통합 20건은 재실행하지 않았다. 업무 Java 코드와 스키마·기존 공유 데이터는 변경하지 않았다. 이후 사용자의 커밋·푸시·PR 요청에 따라 이번 하위 작업을 별도 PR로 제출한다. G5 전체 미완료 상태는 유지한다.

## 재실행

```sh
python3 -B -m unittest discover -s tools/verification -p test_service_credentials.py -v
python3 -B -m unittest discover -s tools/verification -p test_boot_jars.py -v
./gradlew :common:test:test --no-daemon --max-workers=2
python3 tools/verification/verify-gateway-ownership.py
```

실제 서비스 검증은 Java 21·Docker·Python requests·준비된 로컬 이미지/Gradle 캐시와 KIROCREW_SCRATCH가 필요하다. 실행 로그와 키 이름 기록은 해당 세션 scratch에 저장한다. 장기 기록은 이 문서와 저장소의 재실행 도구다.

## 남은 G5

실제 운영 비밀 저장·배포·교체와 폐기, 다른 실행기·운영 환경의 비밀 분배 전체, 키 재사용 금지 정책, 외부 웹훅의 재현 구성, 경보·원본 재처리·백업/복구 등의 전체 완료 판정은 남아 있다. 이 작업으로 G5 상위 항목을 완료 처리하지 않는다.
