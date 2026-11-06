# 설계: Download

## 아키텍처 위치

- 포트 8088, 저장소 없음(무상태). 업무 DB·MongoDB·Kafka 소비/발행·Inbox·Outbox가 없다.
- `core/{domain, port, service}` + `api/controller` + `adapter/` + `config` 경계를 따른다.
- Gateway(8080)가 기존 `/api/v1/downloads/**` 라우트와 `MEMBER` 조건, BFF의 access token 전달을 담당한다.
- 요청마다 License → Catalog → 저장소 순으로 조회·확인하고, 결과를 베끼지 않는다.

## 핵심 설계 결정

- **무상태.** 보유권·공개본 사본, 조회 캐시, 발급 이력을 두지 않는다. 요청마다 외부를 다시 확인한다. 서비스 간 잠금·분산 트랜잭션을 두지 않는다.
- **토큰 격리.** 검증한 **원래 사용자 토큰**은 고정된 License 주소에만 Bearer로 전달한다. Catalog·저장소에는 전달하지 않는다. Catalog 내부 GET은 전용 서비스 키만 쓴다.
- **ACTIVE만 발급.** License 상세의 명시적 `ACTIVE`에만 발급한다. `404`·지급 전·미보유·`SUSPENDED`는 `403`. 연계 장애·형식 오류 성공 응답을 미보유로 바꾸지 않는다.
- **상품 ID·코드 대조.** 요청 코드 = License 코드 = Catalog 코드, License `productId` = Catalog `productId`인 현재 `COMPLETED` 공개본만 서명한다.
- **읽기 전용 저장소.** 전용 읽기 계정으로 HEAD 후 presigned GET. 쓰기·삭제·버킷 생성 권한 없음. HEAD 크기와 공개 스냅샷 `fileSize`를 대조한다.
- **자연 멱등.** Inbox 가드가 없다(이벤트 소비 없음). 상태를 만들지 않으므로 반복 요청이 부수 효과를 남기지 않는다.
- **공유 인프라 미의존.** `common:messaging`(Outbox·JPA)을 의존하지 않고 `common:kafka` 수신 정책도 쓰지 않는다. 운영 JAR에 MongoDB·Kafka·Messaging·JPA 의존성이 없다.

## 데이터 모델 (무상태, 모듈 내부 값)

| 값(도메인) | 내용 |
|---|---|
| 보유 상품 | License 상세를 변환한 값. 양수 `productId`·일치하는 `productCode`. 생성 시 유효성 보장 |
| 공개 빌드 | Catalog 내부 응답을 변환한 `PublishedBuild`. `productId`·`productCode`·`buildId`·`version`·`fileSize`·`checksum`·`storagePath` |
| 서명 URL·다운로드 결과 | presigned `downloadUrl`·`expiresAt`과 응답 여섯 필드 |

영속 엔티티·저장소 사본은 없다. HTTP 응답 DTO를 도메인 입력으로 그대로 넘기지 않고 생성 시 검증되는 값으로 변환한다.

## API 계약

| 메서드 | 경로 | 역할 | 동작 |
|---|---|---|---|
| GET | `/api/v1/downloads/{productCode}/ticket` | `MEMBER` | 현재 공개 전체 빌드의 티켓(URL·만료·버전·크기·체크섬) 발급, `no-store` |

- 쿼리 파라미터·과거 버전·플랫폼·델타 선택·manifests API는 없다. 쿼리를 보내면 `400`.
- 성공 응답은 `ApiResponse` 안의 `productCode`·`version`·`fileSize`·`checksum`·`downloadUrl`·`expiresAt` 여섯 필드.
- 업무 경로는 위 GET만 허용, 나머지는 기본 거부. Download 운영 API(`/api/v1/ops/**`)는 만들지 않는다.

## 내부·외부 연동 계약

### License 조회 (사용자 토큰)
- 기존 `GET /api/v1/library/{productCode}`를 그대로 사용한다. License API·스키마·지급 처리는 변경하지 않는다.
- 어댑터가 봉투 성공 여부·본문 타입·양수 `productId`·일치 `productCode`를 검증한다.
- `404` → Download `403`(미보유). 사용자 토큰 `401`·`403` → 같은 인증·권한 오류.

### Catalog 내부 빌드 조회 (서비스 키)
- `GET /internal/v1/products/{productCode}/build`. 응답 필드: `productId`·`productCode`·`releaseRequestId`·`contentVersion`·`buildId`·`version`·`fileSize`·`checksum`·`storagePath`.
- `CatalogProduct.currentReleaseRequestId`로 선택한 `COMPLETED` `CatalogRelease`의 불변 `ProjectSnapshot`에서 구성한다.
- 헤더 `X-Download-Service-Key`, 설정 키 `CATALOG_DOWNLOAD_SERVICE_KEY`를 두 앱에 주입한다. 공통 기본 키·리포 저장 키를 쓰지 않는다.
- 키 미설정·공백이면 Catalog가 내부 경로를 닫는다. 키 있어도 헤더 없음·불일치는 `401`. Gateway·공개 OpenAPI에 노출하지 않는다.
- 어댑터가 봉투·필드 타입·값 규칙을 검증해 `PublishedBuild`로 변환한다.

### 상품 다운로드 제한 (독립 제한)
- Catalog 내부 GET의 `403`·`PRODUCT_DOWNLOAD_RESTRICTED`를 구분해 Download `403`으로 처리하고 저장소 HEAD·서명을 실행하지 않는다.
- 그 밖의 내부 `403`·인증·응답 오류는 `503`. 판매 제한 상태는 다운로드 판단에 쓰지 않는다.

### 저장소 (S3/MinIO, 읽기 전용)
- `BuildStorage` 포트를 `S3BuildStorage`가 구현한다. 전용 읽기 전용 계정(`GetObject`·`ListBucket`), 쓰기·삭제 권한 없음.
- 설정 빌드 버킷·비어 있지 않은 키만 허용. HEAD로 존재·크기 확인 후 presigned GET 서명.
- 내부 SDK endpoint와 클라이언트 접속 presign endpoint를 구분하고, 생성 후 URL의 host·port를 바꾸지 않는다.

## 오류·장애 처리

| 상황 | 응답 |
|---|---|
| JWT 없음·서명/issuer/audience 오류·만료 | `401`, 업무 조회·발급 없음 |
| `MEMBER` 없음 | `403`, 업무 조회·발급 없음 |
| 상품 코드 형식·쿼리 입력 오류 | `400` |
| License `404`, 지급 전·미보유 | `403`, Catalog·저장소 조회 없음 |
| 이용권 `SUSPENDED` | `403` |
| 상품 `PRODUCT_DOWNLOAD_RESTRICTED` | `403`, 저장소 HEAD·서명 없음 |
| License가 사용자 토큰에 `401`·`403` | 같은 인증·권한 오류로 종료 |
| 보유 확인 후 현재 공개 상품 없음 | `404`, URL 발급 없음 |
| License·Catalog 타임아웃/연결 실패/5xx·잘못된 응답 | `503`, 미보유로 처리하지 않음 |
| Catalog 서비스 키 거절·상품 ID/코드 불일치·잘못된 저장 참조 | `503`, URL 발급 없음 |
| 참조 객체 없음·파일 크기 불일치 | `409`, 다른 빌드로 대체하지 않음 |
| 저장소 중단·자격증명 거절·SDK 통신 오류 | `503`, URL 발급 없음 |

- HTTP·SDK에 유한한 제한 시간(초기 연결 2초·읽기 5초, 저장소 호출 5초)을 둔다. HTTP 재시도·배경 작업 없음, SDK 재시도도 전체 제한 안.
- 외부 조회를 묶는 DB 트랜잭션은 없다. 실패·재시작으로 주문·보유권·공개본을 변경하지 않는다.

## 이벤트 계약

- 외부 이벤트 수신·발행이 없다. Kafka 리스너·Outbox가 없다.

## 외부 연동

| 포트 | 스텁 | 실제 어댑터 | 선택 방법 |
|---|---|---|---|
| `BuildStorage` (download) | — | `S3BuildStorage` | 읽기 전용 S3/MinIO HEAD·presigned GET |

- License·Catalog HTTP 어댑터는 `RestClient` 기반 두 개. 저장소 어댑터 한 개(HEAD·presigned GET).
- CDN 전략·자체 HMAC 토큰·파일 중계는 제공하지 않는다.

## 구현 구성

| 위치 | 최소 구성 |
|---|---|
| `core.domain` | 보유 상품·공개 빌드·서명 URL·다운로드 결과 값, 모듈 내부 값 검증 |
| `core.service` | `ReadBuildDownload` 하나가 보유 확인·공개본 대조·파일 접근을 조율 |
| `core.port` | License 보유 조회·Catalog 빌드 조회·빌드 저장소 접근 경계 |
| `adapter` | `RestClient` HTTP 어댑터 두 개, HEAD·presigned GET 저장소 어댑터 한 개 |
| `api.controller` | 단일 ticket GET·응답 DTO·`no-store`, 인증 요청 입력 해석 |
| `config` | 공통 `ApiJwtSecurity`, HTTP/SDK 클라이언트·설정 검증·객체 조립 |

- 설정 바인딩은 `config`에 둔다. 업무 코드·어댑터에는 값·`Duration`·구성된 클라이언트만 전달한다.
- License·Catalog base URL, Catalog 서비스 키, 읽기 전용 저장소 자격증명·버킷을 필수 설정으로 검증한다. 누락·공백·잘못된 TTL/주소에는 기동을 거절하고 root 계정·기본 공유 키로 대체하지 않는다.
- 사용자 access token은 HTTP 경계에서 준비하며 Spring `Jwt`·서블릿 요청 객체·외부 응답 DTO를 도메인에 전달하지 않는다.

## 다운로드 제한의 저장소 경계

- Download 읽기 계정 IAM(`infra/minio/policies/download-reader.json`)은 허용 버킷의 GetObject/ListBucket과 예약 태그 `stove-download-blocked=true` 객체의 GetObject **명시적 거절**을 함께 가진다. 실제 버킷으로 맞추고 기존 필요한 권한을 보존한다. 고정 MinIO는 인증 요청에 IAM을 적용하므로 버킷 정책만으로 대체하지 않는다.
- Catalog는 이 읽기 계정을 적용 결과 확인(1바이트 GET)에만 쓴다. 태그 쓰기·확인·재시도는 Catalog 스펙을 따른다. Download는 제한을 위해 저장소 쓰기 권한·DB·Kafka를 추가하지 않는다.
- 배포 순서: Download의 새 오류 해석(`PRODUCT_DOWNLOAD_RESTRICTED` 구분)을 먼저 적용하고 Catalog 관리자 경로는 뒤에 연다.

## 검증 요약

2026-10-18 MVP(단위 93·통합 8, 실제 열 앱 15단계)와 2026-11-02 다운로드 제한(관련 348건) 결과는 [근거 색인](../../../docs/evidence.md)을 따른다.

## 사용 안내

| 환경변수 | 의미 |
|---|---|
| `LICENSE_BASE_URL`·`CATALOG_BASE_URL` | 고정된 License·Catalog 서비스 주소 |
| `CATALOG_DOWNLOAD_SERVICE_KEY` | Catalog·Download에 같은 값으로 주입하는 내부 빌드 조회 키(기본값 없음) |
| `JWT_ISSUER_URI`·`JWT_JWK_SET_URI` | Auth의 issuer·공개 키 주소 |
| `STORAGE_BUCKET` | Studio가 전체 빌드를 저장한 버킷 |
| `DOWNLOAD_STORAGE_ACCESS_KEY`·`DOWNLOAD_STORAGE_SECRET_KEY` | 해당 버킷의 읽기 전용 계정(root 대체 없음) |
| `STORAGE_ENDPOINT`·`STORAGE_PRESIGN_ENDPOINT` | HEAD용 내부 주소·클라이언트 접속 주소(AWS 기본 주소면 생략), `STORAGE_REGION` 기본 us-east-1 |
| `DOWNLOAD_BUILD_URL_TTL` | URL 유효기간, 기본 PT5M, 1초~15분 |

- 공개·구매·지급을 마친 MEMBER access token으로 `GET /api/v1/downloads/{productCode}/ticket`을 호출한다. 쿼리 파라미터는 보내지 않는다.
- `downloadUrl`은 그대로 사용한다. host·port를 바꾸면 서명이 맞지 않는다. 파일 바이트는 Download JVM을 거치지 않는다. 받은 뒤 응답의 `fileSize`·`checksum`(SHA-256)과 대조한다.
- 오류별 다음 행동: 401 재인증, 403 보유권/정지/`PRODUCT_DOWNLOAD_RESTRICTED` 확인(PAID 직후 미보유는 지급 대기), 404 현재 공개 상품 없음, 409 참조 파일 보존·복구 확인, 503 연계 원인 복구 뒤 재요청.
- 구매 시 버전에 제한되지 않으므로 후속 공개본도 같은 보유권으로 받는다. 작업본 변경만으로 다운로드 파일이 바뀌지 않는다.
- Gateway에는 `/internal/**` 라우트가 없다. 내부 Catalog 주소·키를 외부에 공개하지 않는다.
