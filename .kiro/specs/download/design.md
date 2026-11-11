# 설계: Download

> 상태: 작성 중(결정 150·178). 모듈을 넘는 계약은 [플랫폼 설계](../platform/design.md)가 정본이다. 경로 줄임: `download:` = `apps/download/src/main/java/com/stove/download/`. 줄 번호는 2026-11-09 `docs/spec-foundation` 기준이다.

## 아키텍처 위치

- 포트 8088. 업무 저장소가 없다(플랫폼 design "모듈과 외부 공급자" Download 행). 외부 경계는 객체 저장소 서명 URL 하나다.
- 패키지: `core/{domain, port, service}` + `api/controller` + `adapter`(HTTP 둘, 저장소 하나) + `config`. 의존 방향은 [P1.1](../platform/requirements.md)·AGENTS.md 3절, 강제는 `DownloadArchitectureTest`(공통 `AppArchitectureRules`).
- 동기 의존(플랫폼 design "동기 의존" 표의 두 행이 정본): Download → License `GET /api/v1/library/{code}`(사용자 JWT), Download → Catalog `GET /internal/v1/products/{code}/build`(`X-Download-Service-Key`). Download를 부르는 모듈은 Gateway뿐이다(`/api/v1/downloads/**` 라우트, `apps/gateway/src/main/resources/application.yml:165-168`). Download는 순환에 들지 않는다([P1.2-1](../platform/requirements.md)).
- 비동기 의존 없음: `@KafkaListener`·Outbox가 없고 소비 그룹 21개에 Download 그룹이 없다(플랫폼 design "소비 그룹").
- 흐름 위치: 흐름 1의 마지막 단계 S10(플랫폼 design "흐름 1" 단계 목록). 끝 상태 E1~E7의 Download 열은 "이 공개본 빌드 발급 / 기존 공개본 / 없음"이고, Download가 바꾸는 상태는 없다.
- 운영 JAR 의존성: `spring-boot-starter-web`·`validation`·springdoc·`software.amazon.awssdk:s3`·`common:web`·`common:security`뿐이다(`apps/download/build.gradle`). JPA·Kafka·`common:messaging`·`common:kafka`·MongoDB 의존이 없다.

## 데이터 모델

**없음.** 영속 테이블·Flyway 이행(`apps/download/src/main/resources/`에 `db/` 없음)·엔티티가 없다. 요청 하나 안에서만 사는 core 값만 있다. 모든 값은 생성할 때 검증한다(AGENTS.md 3절).

| core 값 | 칸 | 생성 검증 (`download:core/domain/`) |
|---|---|---|
| `DownloadRequest` | `buyerSubject`, `productCode` | `sub` 공백 아님·100자 이하(아니면 `UNAUTHORIZED`), 코드 형식 R2.1(아니면 `INVALID_REQUEST`) — `DownloadRequest.java:6-12` |
| `OwnedProduct` | `productId`, `productCode` | `productId > 0`, 코드 형식 — `OwnedProduct.java:3-7` |
| `PublishedBuild` | `productId`·`productCode`·`releaseRequestId`·`contentVersion`·`buildId`·`version`·`fileSize`·`checksum`·`storagePath` | ID·버전·크기 양수, `version` 1~100자, `checksum` 64자 16진, `storagePath` 1~2000자 — `PublishedBuild.java:3-12` |
| `SignedUrl` | `url`, `expiresAt` | http/https, host 있음, userinfo 없음, 만료 시각 있음 — `SignedUrl.java:6-14` |
| `DownloadTicket` | 응답 여섯 값 | `PublishedBuild` + `SignedUrl`에서만 만든다 — `DownloadTicket.java:7-10` |

## 상태 모델

**이 모듈에는 상태 모델이 없다.** 근거:

| 확인 | 결과 | 위치 |
|---|---|---|
| 영속 상태 | 테이블·엔티티·이행 파일 없음 | `apps/download/src/main/resources/`(application.yml만), `@Entity` 0건 |
| 소비 이벤트 | 없음 | `@KafkaListener` 0건, Kafka 의존 없음(`build.gradle`) |
| 예약 작업 | 없음 | `@Scheduled` 0건 |
| 운영자 명령 | 없음 | `SecurityConfig.java:23-27` — 업무 경로는 ticket GET 하나, 나머지 `denyAll`, `/api/v1/ops/**` 없음 |
| 메모리 상태 | 없음 | 빈은 설정 값·클라이언트만 가진다(`DownloadClientConfiguration`·`StorageConfiguration`). 준비 상태·캐시 없음 |

그래서 점검표 2의 "상태 × 트리거 표"는 **트리거 하나(MEMBER의 ticket GET)에 대한 요청 처리 판정 표**가 된다. 판정 입력은 요청 자체와 세 외부 사실(License 보유권, Catalog 공개본·제한, 저장소 객체)이고, 어느 행도 상태를 바꾸지 않는다. 다른 트리거(이벤트·예약 작업·운영자 명령)는 존재하지 않으므로 행이 없다.

### 요청 처리 판정 표 (빈칸 0)

위에서 아래로 처음 맞는 행에서 끝난다. "부른 곳"은 그 행까지 실제로 부른 외부다. **모든 행의 부수 효과는 없음**(Download·License·Catalog·저장소 어느 상태도 바뀌지 않는다). "코드 차이"가 있는 행은 [tasks.md](tasks.md)를 본다.

| # | 단계 | 조건 | 응답 (HTTP · 오류 코드) | 부른 곳 | 근거 · 코드 차이 |
|---|---|---|---|---|---|
| J1 | 경로 | ticket GET이 아닌 업무 경로·메서드(옛 `/manifests` 등), 익명 | 401 | 없음 | R2.4, `SecurityConfig.java:27` |
| J2 | 경로 | 같은 경우, 인증됨 | 403 | 없음 | R2.4 |
| J3 | 인증 | JWT 없음·서명·issuer·audience(`esd-api`) 오류·만료 | 401 `UNAUTHORIZED` | 없음 | R1.4, `ApiJwtSecurity` |
| J4 | 인증 | 유효 JWT, `MEMBER` 역할 없음 | 403 `FORBIDDEN` | 없음 | R1.5, `SecurityConfig.java:26` |
| J5 | 입력 | 쿼리 파라미터가 하나라도 있음 | 400 `INVALID_REQUEST` | 없음 | R2.3, `DownloadController.java:35` |
| J6 | 입력 | `sub` 공백·100자 초과 | 401 `UNAUTHORIZED` | 없음 | R1.2, `DownloadRequest.java:8-9` |
| J7 | 입력 | 상품 코드 형식 위반 | 400 `INVALID_REQUEST` | 없음 | R2.2, `DownloadRequest.java:10-11` |
| J8 | License | 연결 실패·제한 시간 초과 | 503 `UPSTREAM_UNAVAILABLE` | License | R9.3·R9.4 |
| J9 | License | 3xx(리다이렉트를 따르지 않음) | 503 | License | R6.10, `DownloadClientConfiguration.java:24-25` |
| J10 | License | 401 | 401 `UNAUTHORIZED` | License | R3.6 — [결정 필요 M-DL-2] |
| J11 | License | 403 | 403 `FORBIDDEN` | License | R3.6 — [결정 필요 M-DL-2] |
| J12 | License | 404 + 계약 본문(`success=false`, `error.code=NOT_FOUND`) | 403 (열린 보유권 없음, 코드는 [M-DL-1]) | License | R3.4·R3.8, 결정 93·96·97 |
| J13 | License | 404 + 그 밖의 본문 | 503 | License | R3.9 — **코드 차이 DL-T1**(지금 403) |
| J14 | License | 그 밖의 4xx·5xx | 503 | License | R9.3, `LicenseHttpClient.java:26` |
| J15 | License | 200, 봉투가 성공 아님·`data`가 객체 아님·JSON 아님 | 503 | License | R9.3, `HttpResponseValues.java:10-14` |
| J16 | License | 200, `status`=`SUSPENDED` | 403 (정지, 코드는 [M-DL-1]) | License | R3.5, `LicenseHttpClient.java:30-32` |
| J17 | License | 200, `status` 없음·`ACTIVE`/`SUSPENDED` 밖의 값 | 503 | License | R3.10, `LicenseHttpClient.java:33` |
| J18 | License | 200 `ACTIVE`, `productId`가 양의 정수 아님·`productCode`가 문자열 아님·형식 위반 | 503 | License | R9.3, `OwnedProduct.java:5` |
| J19 | License | 200 `ACTIVE`, `productCode` ≠ 요청 코드 | 503 | License | R4.3, `LicenseHttpClient.java:36` |
| J20 | Catalog | 연결 실패·제한 시간 초과·3xx | 503 | License, Catalog | R9.3·R6.10 |
| J21 | Catalog | 401(서비스 키 거절·키 미설정으로 닫힘) | 503 | License, Catalog | R4.5 |
| J22 | Catalog | 403 + 계약 본문(`success=false`, `error.code=PRODUCT_DOWNLOAD_RESTRICTED`) | 403 `PRODUCT_DOWNLOAD_RESTRICTED` | License, Catalog | R5.1·R5.2, `CatalogBuildHttpClient.java:29-35` |
| J23 | Catalog | 403 + 그 밖의 본문 | 503 | License, Catalog | R5.3 |
| J24 | Catalog | 404 + 계약 본문(`error.code=NOT_FOUND`) | 404 `NOT_FOUND` | License, Catalog | R4.4 |
| J25 | Catalog | 404 + 그 밖의 본문 | 503 | License, Catalog | R4.7 — **코드 차이 DL-T1**(지금 404) |
| J26 | Catalog | 그 밖의 4xx·5xx(Catalog 정합 실패 500 포함) | 503 | License, Catalog | R9.3, `CatalogBuildHttpClient.java:39` |
| J27 | Catalog | 200, 봉투·칸 타입·값 규칙 위반(ID·크기 ≤ 0, `checksum` 형식, `version`·`storagePath` 길이) | 503 | License, Catalog | R9.3, `PublishedBuild.java:7-11` |
| J28 | Catalog | 200, `productCode` ≠ 요청 코드 | 503 | License, Catalog | R4.3, `CatalogBuildHttpClient.java:47` |
| J29 | Catalog | 200, `productId` ≠ License `productId` | 503 | License, Catalog | R4.3·RI-6, `ReadBuildDownload.java:26-27` |
| J30 | 저장 참조 | `storagePath`가 설정 버킷 밖·키 비어 있음 | 503 | License, Catalog | R6.2, `S3BuildStorage.java:34-36` |
| J31 | 저장소 | 메타데이터 조회 통신 오류·제한 시간 초과 | 503 | License, Catalog, 저장소 | R6.9 |
| J32 | 저장소 | 객체 없음 | 409 `CONFLICT` | License, Catalog, 저장소 | R6.4, `S3BuildStorage.java:47-48` |
| J33 | 저장소 | 그 밖의 저장소 오류(자격 거절·차단 표시 거절·5xx) | 503 | License, Catalog, 저장소 | R6.9, `S3BuildStorage.java:49` |
| J34 | 저장소 | 객체 크기 ≠ `fileSize` | 409 `CONFLICT` | License, Catalog, 저장소 | R6.4, `S3BuildStorage.java:40-41` |
| J35 | 서명 | 서명 결과가 `SignedUrl` 규칙 위반 | 500 `INTERNAL_ERROR`, URL을 응답·로그에 남기지 않음 | License, Catalog, 저장소 | R7.4 — **코드 차이 DL-T4**(예외 메시지 로그) |
| J36 | 발급 | 위에 걸리지 않음 | 200 티켓 여섯 값, `Cache-Control: no-store` | License, Catalog, 저장소 | R6.5·R7, `DownloadController.java:36-38` |

순서 규칙(행 번호가 곧 순서): 인증 → 입력 → License → Catalog → 저장 참조 → 저장소 → 서명. 그래서 미보유·정지 고객에게는 다운로드 제한·공개 상품 없음·파일 문제를 알리지 않는다(J12·J16이 J22·J24·J32보다 먼저).

### 외부 사실 조합 요약

판정 표를 외부 사실 기준으로 다시 본 것이다. 행 = License 라이브러리 상세, 열 = Catalog 내부 빌드 응답(저장소는 Catalog가 "공개본 있음"일 때만 본다).

| License \ Catalog | 공개본 있음(200, 연결 맞음) | 제한(J22) | 공개 상품 없음(J24) | 장애·계약 밖(J20·J21·J23·J25~J29) |
|---|---|---|---|---|
| `ACTIVE`, 연결 맞음 | 저장소 판정(J30~J36) | 403 제한 | 404 | 503 |
| `SUSPENDED` | 403 정지 — Catalog 안 부름 | 같음 | 같음 | 같음 |
| 열린 보유권 없음(404 계약 본문) | 403 미보유 — Catalog 안 부름 | 같음 | 같음 | 같음 |
| 장애·계약 밖(J8·J9·J13~J15·J17~J19) | 503 — Catalog 안 부름 | 같음 | 같음 | 같음 |
| 사용자 토큰 거절(401·403) | 401·403 — Catalog 안 부름 | 같음 | 같음 | 같음 |

## API 계약

| 메서드 | 경로 | 역할 | 요청 | 성공 | 오류 |
|---|---|---|---|---|---|
| GET | `/api/v1/downloads/{productCode}/ticket` | `MEMBER` | 경로 변수 하나, 본문·쿼리 없음 | 200 `ApiResponse<DownloadTicketResponse>` — `productCode`·`version`·`fileSize`·`checksum`·`downloadUrl`·`expiresAt`, `Cache-Control: no-store` | 판정 표 J1~J35 |

- 멱등 키: 없다. 상태를 만들지 않으므로 같은 요청을 몇 번 보내도 부수 효과가 없고, 요청마다 새 URL(새 `expiresAt`)을 준다(자연 멱등, S10 "중복 전달" 행).
- 오류 본문은 공통 `ApiResponse.fail`(`common/web`의 `GlobalExceptionHandler`). 오류 코드: `UNAUTHORIZED`·`FORBIDDEN`·`INVALID_REQUEST`·`NOT_FOUND`·`CONFLICT`·`UPSTREAM_UNAVAILABLE`·`INTERNAL_ERROR`(공통 `ErrorCode`), `PRODUCT_DOWNLOAD_RESTRICTED`(`download:core/domain/DownloadError.java`). 403 거절 사유의 코드 구분은 [결정 필요 M-DL-1].
- OpenAPI: 커밋한 스냅샷(`apps/download/src/integrationTest/resources/openapi/download.json`)에 이 경로 하나만 있다(`DownloadIntegrationTest#committedOpenApiHasOneEndpointAndMatchesSnapshot`). Download 운영 API(`/api/v1/ops/**`)는 만들지 않는다.
- 클라이언트 행동(사용 안내): 401 재인증, 403 보유권·정지·제한 확인, 404 현재 공개 상품 없음, 409 파일 보존 확인 뒤 재요청, 503 잠시 뒤 재요청. 받은 파일은 `fileSize`·`checksum`(SHA-256)과 대조한다. `downloadUrl`의 host·port를 바꾸면 서명이 맞지 않는다.

## 이벤트

**발행 없음, 소비 없음.** 플랫폼 이벤트 목록에 Download가 발행·소비하는 현행 행이 없다. 옛 Download 사본용 이벤트 `LicenseIssued`(#35)·`LicenseRevoked`(#36)는 "제거 대상"이고 Download는 받지 않는다(플랫폼 design "`EventType` 상수" 표, 제거 절차 [P2.8-2](../platform/requirements.md)·T30). 그래서 Inbox·업무 멱등 키·DLT·재발행 경로(P2.2-7)도 이 모듈에는 없다.

보유권 정지·복구·회수([P3.18](../platform/requirements.md)·P3.24·P3.25)와 공개본 교체([P3.5](../platform/requirements.md))는 이벤트로 Download에 오지 않는다. Download는 다음 요청 때 License·Catalog 정본을 다시 읽어 반영한다(S10 "순서 역전"·"늦은 도착" 행).

## 모듈 안 불변식

| # | 불변식 | 지키는 곳 | 확인 테스트 |
|---|---|---|---|
| DL-I1 | License가 `ACTIVE`를 주기 전에는 Catalog·저장소를 부르지 않는다 | `ReadBuildDownload.execute`(`ReadBuildDownload.java:23-25` 순서), `LicenseHttpClient.require` | `ReadBuildDownloadTest#unownedOrUnavailableLicenseStopsBeforeCatalogAndStorage`, `DownloadIntegrationTest#missingOrUnknownAccessStateCannotReachCatalogOrStorage` |
| DL-I2 | 서명하는 객체는 요청 코드 = License 코드 = Catalog 코드, License `productId` = Catalog `productId`인 공개본의 것이다(플랫폼 RI-6의 Download 쪽) | `ReadBuildDownload.java:24·26-27`, `LicenseHttpClient.java:36`, `CatalogBuildHttpClient.java:47` | `ReadBuildDownloadTest#mismatchedProductIdentityCannotSignAnotherProductsFile`·`#wrongOwnedProductCodeStopsBeforeCatalog`, `DownloadHttpClientsTest#rejectsMismatchedCodesAndBuildFields` |
| DL-I3 | 다운로드 제한이면 저장소를 부르지 않는다 | `CatalogBuildHttpClient.java:29-35`(예외로 끝남) | `ReadBuildDownloadTest#aProductDistributionRestrictionPreventsEveryStorageCall`, `DownloadIntegrationTest#productRestrictionIsDistinguishedFromOwnershipAndUpstreamFailures` |
| DL-I4 | 티켓의 `version`·`fileSize`·`checksum`은 한 번 읽은 `PublishedBuild` 하나에서만 나오고, 저장소 객체 크기와 `fileSize`가 같다 | `DownloadTicket.of`, `S3BuildStorage.java:39-41` | `DownloadIntegrationTest#actualSignedDownloadMatchesBytesSizeAndChecksumAndCannotBeTampered`·`#rejectsMissingChangedAndInvalidStorageObjects` |
| DL-I5 | 사용자 토큰은 License로만, 서비스 키는 Catalog로만 가고, 리다이렉트로 다른 곳에 가지 않는다 | `LicenseHttpClient.java:20`, `CatalogBuildHttpClient.java:27`, `DownloadClientConfiguration.java:24-25`(`Redirect.NEVER`) | `DownloadHttpClientsTest#forwardsUserTokenOnlyToLibraryAndServiceKeyOnlyToCatalog`·`#redirectsNeverForwardCredentialsToAnotherRequest` |
| DL-I6 | 계약 밖 응답·장애는 미보유(403)·없는 상품(404)이 되지 않는다 | `HttpResponseValues.invalid`, 각 어댑터 `onStatus` | `DownloadHttpClientsTest#rejectsMalformedSuccessResponses`·`#mapsCatalogStatuses`, `DownloadIntegrationTest#upstreamFailuresAreNotOwnershipDenialsAndProductMismatchCannotSign` — **404 본문 미대조는 DL-T1** |
| DL-I7 | 서명은 설정된 빌드 버킷 안의 비어 있지 않은 키에만 한다 | `S3BuildStorage.java:34-37` | `DownloadIntegrationTest#rejectsMissingChangedAndInvalidStorageObjects` |
| DL-I8 | 저장소 자격은 읽기 전용이다(쓰기·삭제 거절) | 실행 환경 정책 `infra/minio/policies/download-reader.json:4-13` | `DownloadIntegrationTest#storageAccountCanReadButCannotWriteOrDelete` |
| DL-I9 | 사용자 토큰·서비스 키·서명 URL이 로그에 남지 않는다 | 로그 문 없음(운영 코드에 토큰·URL 로그 없음) | 없음 → tasks DL-T5 |
| DL-I10 | 무상태: 운영 JAR에 JPA·Kafka·messaging 의존과 리스너·예약 작업이 없다 | `apps/download/build.gradle` | 없음(2026-10-18 수동 확인만, 근거 색인) → tasks DL-T6 |
| DL-I11 | URL 유효기간은 1초~15분, 필수 설정이 없으면 기동하지 않는다 | `ObjectStorageProperties.java:10-21`, `DownloadClientProperties.java:10-19` | `DownloadConfigurationTest#requiresExplicitStorageCredentialsAndBoundedTtl`·`#requiresExplicitServiceKeyAndPositiveTimeouts`·`#requiresFixedHttpBaseUrls` — JWT 설정은 DL-T3 |

모듈을 넘는 불변식: RI-6(플랫폼 design "끝 상태와 정합 조건"). 돈 불변식 I1~I20에는 Download가 지키는 행이 없다.

## 외부 연동과 adapter 메모

| core.port | adapter | 대상 | 인증 | 오류 해석 |
|---|---|---|---|---|
| `OwnedProducts.require(productCode, accessToken)` | `adapter/LicenseHttpClient` (`RestClient`) | License `GET /api/v1/library/{code}` | 사용자 Bearer | 판정 표 J8~J19 |
| `PublishedBuilds.require(productCode)` | `adapter/CatalogBuildHttpClient` (`RestClient`) | Catalog `GET /internal/v1/products/{code}/build` | `X-Download-Service-Key` | 판정 표 J20~J29 |
| `BuildStorage.download(PublishedBuild)` | `adapter/storage/S3BuildStorage` (AWS SDK v2 `S3Client`·`S3Presigner`) | 객체 저장소 | 읽기 전용 정적 자격 | 판정 표 J30~J35 |

받는 쪽 계약(Download가 기대는 값 — 다른 모듈 스펙이 맞춰야 한다):

- License 라이브러리 상세: 열린 보유권(`ACTIVE`·`SUSPENDED`) 중 `ACTIVE`가 있으면 그것, 없으면 가장 최근 `SUSPENDED`를 `200`으로, 열린 것이 없으면 `404 NOT_FOUND`(`license:core/service/ReadLicense.java:57-67`). `ENDED`는 돌려주지 않는다. 응답 칸 `licenseId`·`productId`·`productCode`·`title`·`acquiredAt`·`status`(`license:api/controller/dto/LicenseResponse.java:8-9`) 중 Download는 `productId`·`productCode`·`status`만 쓴다.
- Catalog 내부 빌드: `200` 칸 `productId`·`productCode`·`releaseRequestId`·`contentVersion`·`buildId`·`version`·`fileSize`·`checksum`·`storagePath`, 상품 없음 `404 NOT_FOUND`, 제한(`download_restricted` 또는 `download_storage_status` ≠ `APPLIED`) `403 PRODUCT_DOWNLOAD_RESTRICTED`, 공개본 정합 실패 `500`, 키 없음·불일치 `401`(`catalog:core/service/ReadPublishedBuild.java:26-53`, `catalog:config/SecurityConfig.java:48-53`).

adapter 메모(공급자 규칙은 여기만):

- 저장 참조 형식은 `s3://<버킷>/<객체 키>`이고, `S3BuildStorage`가 설정 버킷 접두사를 확인한 뒤 키를 떼어 SDK의 bucket·key로 쓴다. 메타데이터 확인은 `HeadObject`, 객체 없음은 SDK `S3Exception` 상태 404, 그 밖의 `S3Exception`·`SdkClientException`은 장애.
- 서명은 `S3Presigner.presignGetObject`(로컬 계산, 네트워크 없음), 유효기간 = 설정 TTL, `expiresAt` = `PresignedGetObjectRequest.expiration()`. 서명용 endpoint(`STORAGE_PRESIGN_ENDPOINT`)와 내부 SDK endpoint(`STORAGE_ENDPOINT`)를 나누고, 만든 URL의 host·port를 바꾸지 않는다. 사용자 지정 endpoint면 path-style 접근.
- 다운로드 제한의 저장소 쪽: Download 읽기 계정의 IAM 정책(`infra/minio/policies/download-reader.json`)이 버킷의 `s3:GetObject`·`s3:ListBucket`을 허용하고, 예약 태그 `stove-download-blocked=true`가 붙은 객체의 `s3:GetObject`를 명시적으로 거절한다(`:14-20`). 태그를 붙이고 떼는 것은 Catalog 태그 전용 계정이다(`catalog:adapter/storage/S3BuildDistribution.java:21`). MinIO는 인증 요청에 IAM을 적용하므로 버킷 정책으로 대체하지 않는다. 태그가 붙은 객체는 `HeadObject`도 거절되므로 J33(503)이 되지만, Catalog가 `APPLIED`로 확인한 제한은 J22가 먼저 걸러 실제로는 닿지 않는다.
- Catalog는 이 Download 읽기 계정을 제한 적용 결과 확인(1바이트 GET)에만 쓴다(Catalog 스펙). 배포 순서는 Download의 `PRODUCT_DOWNLOAD_RESTRICTED` 해석을 먼저, Catalog 관리자 경로를 뒤에(결정 50 적용 기록).
- CDN·자체 HMAC 토큰·파일 중계는 없다. 파일 바이트는 Download JVM을 거치지 않는다.

## 설정

| 설정 키 (`application.yml`) | 환경변수 | 기본값 | 검증 |
|---|---|---|---|
| `stove.download.license-base-url` | `LICENSE_BASE_URL` | 없음(필수) | http/https, host 있음, userinfo·query·fragment·경로 없음 — `DownloadClientProperties.java:21-28` |
| `stove.download.catalog-base-url` | `CATALOG_BASE_URL` | 없음(필수) | 같음 |
| `stove.download.catalog-service-key` | `CATALOG_DOWNLOAD_SERVICE_KEY` | 없음(필수, 공백 거절) | `DownloadClientProperties.java:13-14`. Catalog `stove.catalog.download-service-key`와 같은 값을 배포 환경이 주입(결정 86) |
| `stove.download.connect-timeout` / `read-timeout` | — | 2s / 5s | 양수 |
| `stove.download-storage.bucket` | `STORAGE_BUCKET` | 없음(필수) | 공백 거절 |
| `stove.download-storage.access-key` / `secret-key` | `DOWNLOAD_STORAGE_ACCESS_KEY` / `DOWNLOAD_STORAGE_SECRET_KEY` | 없음(필수) | 공백 거절, 관리자 계정 대체 없음 |
| `stove.download-storage.endpoint` / `presign-endpoint` | `STORAGE_ENDPOINT` / `STORAGE_PRESIGN_ENDPOINT` | 비움(= 공급자 기본 주소) | 있으면 http/https·경로 없음 |
| `stove.download-storage.region` | `STORAGE_REGION` | `us-east-1` | 공백 거절 |
| `stove.download-storage.presign-ttl` | `DOWNLOAD_BUILD_URL_TTL` | `PT5M` | 1초~15분 |
| `stove.download-storage.call-timeout` | — | 5s | 양수 |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` / `jwk-set-uri` | `JWT_ISSUER_URI` / `JWT_JWK_SET_URI` | **목표: 없음(필수)**. 지금 `http://localhost:8091`·`…/oauth2/jwks` | R9.8 — **코드 차이 DL-T3** |

운영/테스트 차이: 업무 설정에 테스트 전용 경로·기본값·우회 스위치가 없다(통합 테스트는 `@DynamicPropertySource`로 값만 넣는다, `DownloadIntegrationTest.java:125-145`). 예외는 JWT 주소의 로컬 기본값 하나(DL-T3, 모든 모듈 공통 — notes F-5). `stove.messaging.ops.enabled: false`는 Download에 messaging 의존이 없어 효과가 없는 남은 키다(DL-T7). 추적 샘플링 `TRACING_SAMPLE_RATE` 기본 1.0과 OTLP 주소 기본값은 관측 설정이라 업무 계약 밖이다(notes F-5).
