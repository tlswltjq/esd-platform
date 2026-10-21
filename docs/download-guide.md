# Download 실행·빌드 다운로드 확인

[문서 목록](README.md) · [MVP 범위·완료 조건](download-mvp-plan.md) · [License 조회](license-guide.md) · [Catalog 공개본](catalog-guide.md)

Download는 본인의 상품 보유권과 현재 공개 빌드를 HTTP로 조회하고 전체 ZIP의 S3/MinIO 서명 URL을 발급한다.
업무 DB·MongoDB·Kafka·동기화 작업은 사용하지 않는다. 파일 전송은 저장소가 맡는다.

## 로컬 OrbStack 검증

2026-10-18 사용자 지시에 따라 빌드·테스트·실행을 로컬 OrbStack에서 수행한다.
저장소 루트에서 Docker와 JDK 21을 준비한 뒤 다음과 같이 실행한다.

```bash
export DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
./gradlew :apps:download:test :apps:download:integrationTest :apps:catalog:test :apps:catalog:integrationTest -Pstove.infraTest.maxParallel=1 --no-daemon
python3 tools/verification/verify-download.py
```

MinIO 이미지는 Gradle 통합 테스트와 검증 스크립트가 자동 준비한다. `infra/minio`의 고정 소스를 Go 컨테이너에서 빌드하고 Jib으로 패키징한다.
단위 검사는 실제 HTTP 대역의 요청·응답·오류를, 통합 검사는 실제 JWT·MinIO 파일·읽기 전용 계정을 사용한다.
통합 OpenAPI 생성은 최초 계약 갱신 때만 `-Dstove.openapi.update=true`를 사용하고 이후 갱신 옵션 없이 대조한다.

실제 구매 검증은 Auth·Gateway·Studio·Review·Catalog·Store·Order·Payment·License·Download 열 앱을 실행한다.
MySQL·Redis·Kafka·MinIO는 전용 Compose 프로젝트와 임의 포트로 생성한다. 앱은 로컬 JAR를 전용 포트에 실행한다.
기본 앱 포트는 각 내부 포트 + 32000이며 `DOWNLOAD_TEST_PORT_OFFSET`으로 변경할 수 있다.
실행 증거는 무시 경로 `runs/stove-download-check-*/`의 앱 로그·단계별 JUnit XML·summary.json에 남는다.
종료 시 이번 실행의 앱과 컨테이너·볼륨만 정리한다. 기존 공유 DB·버킷·MongoDB 자료를 이관하거나 삭제하지 않는다.

## 실행 설정

| 환경변수 | 의미 |
|---|---|
| `LICENSE_BASE_URL` | 고정된 License 서비스 주소 |
| `CATALOG_BASE_URL` | 고정된 Catalog 서비스 주소 |
| `CATALOG_DOWNLOAD_SERVICE_KEY` | Catalog·Download에 같은 값으로 주입하는 내부 빌드 조회 키 |
| `JWT_ISSUER_URI`, `JWT_JWK_SET_URI` | 완료된 Auth의 issuer·공개 키 주소 |
| `STORAGE_BUCKET` | Studio가 전체 빌드를 저장한 버킷 |
| `DOWNLOAD_STORAGE_ACCESS_KEY`, `DOWNLOAD_STORAGE_SECRET_KEY` | 해당 버킷의 읽기 전용 계정 |
| `STORAGE_ENDPOINT` | Download가 HEAD를 수행할 S3/MinIO 내부 주소. AWS 기본 주소 사용 시 생략 |
| `STORAGE_PRESIGN_ENDPOINT` | 클라이언트가 파일을 받을 주소. 생략 시 내부 주소 또는 AWS 기본 주소 사용 |
| `STORAGE_REGION` | 기본 us-east-1 |
| `DOWNLOAD_BUILD_URL_TTL` | URL 유효기간. 기본 PT5M, 1초~15분 |

Download에는 기본 서비스 키나 root 저장소 자격증명 대체값이 없다.
Catalog는 내부 키가 없으면 내부 경로를 닫고 기존 공개·출시 동작을 유지한다.
검증 스크립트는 전용 키와 읽기 전용 계정을 직접 준비한다. 공용 Compose 실행에는 별도로 준비한 값을 주입한다.
Download 계정으로 업로드·삭제·버킷 생성을 허용하지 않는다. HEAD 누락 판별에 필요한 ListBucket 읽기 권한은 기존 Review 정책과 같이 구성한다.
내부 Catalog 주소·키를 외부로 공개하지 않으며 Gateway에는 `/internal/**` 라우트가 없다.

## 사용자 호출

실제 공개·구매·지급을 완료한 MEMBER access token으로 요청한다.
아래 Gateway 주소는 일반 E2E 예시이며 독립 검증 스크립트의 기본 Gateway는 40080이다.

```bash
curl -fsS -H "Authorization: Bearer $MEMBER_TOKEN" \
  http://127.0.0.1:18080/api/v1/downloads/GAME-001/ticket
```

성공 응답의 data는 productCode·version·fileSize·checksum·downloadUrl·expiresAt 여섯 필드다.
`Cache-Control: no-store`를 적용하며 클라이언트는 URL을 그대로 사용한다. 발급 후 주소의 host·port를 바꾸면 서명이 맞지 않는다.
파일을 받은 뒤 응답의 크기·SHA-256과 대조한다. 파일 바이트는 Download JVM을 거치지 않는다.

쿼리 파라미터·플랫폼·아키텍처·설치 버전·과거 버전 선택과 manifests API는 지원하지 않는다.
JWT sub로 본인을 식별하고 X-Member-Id·member_id를 주체 선택에 사용하지 않는다.
구매 시 버전에 제한되지 않는 상품 보유권으로 후속 공개본도 받는다. 작업본 변경만으로 다운로드 파일이 바뀌지는 않는다.

## 오류와 복구

| 응답 | 의미·다음 행동 |
|---|---|
| 400 | 상품 코드 또는 쿼리 입력 오류. 코드만 전달 |
| 401 | 사용자 JWT가 없거나 유효하지 않음. 재인증 |
| 403 | MEMBER 없음 또는 해당 사용자 보유권 없음. PAID 직후라면 License 지급 상태 확인 |
| 404 | 보유 확인 후 Catalog에 현재 공개 상품 없음 |
| 409 | 공개본의 참조 파일 누락 또는 크기 불일치. 해당 파일의 보존·복구 확인 |
| 503 | License·Catalog·저장소 통신/인증/응답 오류 또는 상품 연결 불일치. 원인 복구 후 재요청 |

License·Catalog 중단 중에는 새 URL을 발급하지 않는다. 이미 발급한 URL은 저장소·파일이 유지되는 동안 만료 전 사용 가능하다.
Download 재시작 후 별도 사본 복구 없이 다시 발급한다. 발급 실패로 주문·결제·보유권·공개본을 변경하지 않는다.

서명 URL은 만료 전 가진 사람이 사용할 수 있으며 여러 번 GET할 수 있다. 만료는 새 GET에 적용된다.
보유권 만료·일회용 사용·공유 차단·이미 시작한 전송의 강제 종료를 보장하지 않는다.
HEAD로 확인한 크기는 파일 전체 해시 재검증을 뜻하지 않는다. 공개 파일의 내용을 고유 객체 키에 보존해야 한다.
