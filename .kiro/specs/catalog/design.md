# 설계: Catalog

## 아키텍처 위치

- 포트 8081, 저장소 MySQL.
- `core/{domain, port, service}` + `api/{controller, listener, scheduler}` + `adapter/` + `config` 경계를 따른다.
- 외부 저장소(S3/MinIO 태그) SPI 구현은 `adapter/`에 두고 SDK·자격증명 조립은 `config`가 담당한다.
- Studio가 `ProjectReleaseRequested`로 출시 요청을 보내고, Catalog는 현재 버전의 승인 확인이 끝난 명령으로 신뢰한다. Review 결과를 별도 수집하거나 Studio·Review에 동기 조회하지 않는다.
- Gateway(8080)가 `/api/v1/products/**`의 GET만 허용하고 공개 조회를 익명으로 연결한다. 판매/노출/다운로드 관리 쓰기는 JWT 역할·소유권 검증을 거친다.

## 핵심 설계 결정

- **출시 = 공개본 원자 확정.** Studio 요청 스냅샷을 불변 공개본으로 확정하고 현재 참조를 교체한다. 파일 복제·CDN 배포·판매 가능 여부까지 완료했다는 뜻은 아니다.
- **신구 판정은 contentVersion으로.** 이벤트 시각·releaseRequestId·ZIP 버전 문자열이 아닌 Project의 contentVersion으로 판정한다.
- **판매·노출은 콘텐츠와 독립 버전.** catalogVersion이 판매·노출 변경의 순서를 정하며 콘텐츠 버전을 올리지 않는다. contentVersion보다 작아지지 않는다.
- **판매자 의사와 관리자 제한을 독립 저장.** 두 조건을 각각 저장해 ON_SALE/STOPPED/BLOCKED로 표현하고, 한쪽 해제가 다른 쪽을 바꾸지 않는다. 숨김·BLOCKED가 우선한다.
- **노출은 독립 설정 + 판매 기반 기본값.** 최종 규칙 `!platformListingHidden && listingPreference.isListed(saleStatus)`. 공개 사본에는 최종 listed만 싣는다.
- **공개 접근 판정 일원화.** 공개 상세 허용·새 주문 근거(publicDetailAllowed)를 모듈 내부 ProductPublicAccess 한 곳에서 생산한다.
- **다운로드 제한은 내구 예약 + 저장소 적용 분리.** 상태 저장(202 접수)과 저장소 태그 적용(PENDING/APPLIED/FAILED)을 분리하고 스케줄러가 수렴시킨다.
- **Studio 명령을 신뢰하되 생산 권한은 좁힌다.** 이벤트 형식 검증이 생산자 인증을 대신하지 않으므로 Studio 토픽 생산 권한은 신뢰 서비스에 한정한다. Catalog는 빌드 파일을 다시 검사하거나 다운로드 URL을 발급하지 않고 Studio가 확인한 파일 참조를 보관한다.
- **내부 GET은 용도별 전용 키.** Store 동기화·Order 근거·Download 빌드 조회가 서로 다른 서비스 키를 쓰며 Gateway·공개 OpenAPI에 노출하지 않는다.

## 데이터 모델

| 테이블 | 내용 |
|---|---|
| catalog_product | 내부 productId, 유일 projectId·productCode, ownerSubject, currentReleaseRequestId, saleStatus 저장(판매자 ON_SALE·관리자 restricted), listingPreference·platformListingHidden, downloadRestricted·downloadRestrictionVersion·저장소 상태(PENDING/APPLIED/FAILED)·마지막 성공 확인·다음 실행 시각, catalogVersion, 최초 공개 시각·최종 갱신 시각 |
| catalog_release | releaseRequestId(PK), requestEventId, reviewRequestId, projectId·productCode·contentVersion, 원본 ProjectSnapshot JSON(genres 포함), COMPLETED/REJECTED, 거절 사유, 요청·처리 시각 |
| catalog_sale_change | 판매 상태 변경 전후·버전·JWT sub·권한·시각의 불변 감사 |
| catalog_listing_change | 노출 선호/관리자 조건 변경 전후·버전·sub·시각의 불변 감사 |
| catalog_download_change | 다운로드 제한 변경 전후·사유·버전·sub·시각의 불변 이력과 작업 예약 |
| 공통 Inbox·Outbox | `(event_id, consumer_group)` 멱등 가드와 결과/공개 사본 이벤트 |

- productId는 Catalog 내부 생성, releaseRequestId를 공개본 식별자로 재사용한다. productCode는 Studio 제공 값을 변경 없이 사용한다.
- projectId·productCode·ownerSubject의 최초 연결은 변경할 수 없다. 서비스 간 FK·조인은 사용하지 않는다.
- 공개 목록 정렬용 최초 공개 시각·productId, 이력/검증용 projectId·contentVersion 인덱스를 둔다.
- Flyway: V1~V10 체크섬 유지, V11 catalog_product·catalog_release, V12 판매 상태 이행(기존을 ON_SALE·제한 없음·catalogVersion=contentVersion), V13 다운로드 제한(제한 없음·버전 1·APPLIED), V14 노출(AUTO·숨김 없음), V15 공개 작업·철회 이력·요청 잠금.

## API 계약

| 메서드 | 경로 | 역할 | 동작 |
|---|---|---|---|
| GET | `/api/v1/products` | 공개 | listed=true 공개 상품 페이지 목록(size≤100) |
| GET | `/api/v1/products/by-code/{productCode}` | 공개 | 현재 공개본 상세·saleStatus·catalogVersion. no-store. listed=false는 404 |
| GET·PUT | `/api/v1/products/by-code/{code}/sales` | CREATOR | 본인 판매 의사 조회·ON_SALE/STOPPED 변경. expectedCatalogVersion |
| GET·PUT | `/api/v1/admin/products/{code}/sales/restriction` | ADMIN | 독립 판매 제한 조회·설정·해제. expectedCatalogVersion |
| GET·PUT | `/api/v1/products/by-code/{code}/listing` | CREATOR | 본인 노출 선호(AUTO/LISTED/UNLISTED). expectedCatalogVersion |
| GET·PUT | `/api/v1/admin/products/{code}/listing/restriction` | ADMIN | 독립 강제 숨김(필수 Boolean). expectedCatalogVersion |
| GET·PUT | `/api/v1/admin/products/{code}/downloads/restriction` | ADMIN | 독립 다운로드 제한 조회·설정. 202 접수·필수 사유. expectedRestrictionVersion |
| GET | `/api/v1/admin/catalog-release-jobs` 및 `/{requestId}` | ADMIN | 내구 공개 작업·실패/재시도 조회. no-store |

내부 전용(Gateway·공개 OpenAPI 미노출):

| 경로 | 키 | 용도 |
|---|---|---|
| `/internal/v1/store-products`, `/{code}` | `X-Store-Service-Key` / `CATALOG_STORE_SERVICE_KEY` | Store 전체 동기화(숨긴 상품 포함). 상세에 필수 listed |
| `/internal/v1/order-products/{code}` | `X-Order-Service-Key` / `CATALOG_ORDER_SERVICE_KEY` | Order 주문 근거(식별·가격·saleStatus·publicDetailAllowed) |
| `/internal/v1/products/{productCode}/build` | 전용 서비스 키 | Download 빌드 조회. 제한 시 403/`PRODUCT_DOWNLOAD_RESTRICTED` |

공통: 비로그인 401, 역할 위반 403, 타인/없는 상품 404, 오래된 버전·무료 상품 기대 불일치 409, 누락/잘못된 입력 400. 관리·판매·노출 응답은 no-store. 각 서비스 키는 해당 GET에만 권한이 있다.

## 이벤트 계약

| 방향 | 이벤트 | 토픽 | 비고 |
|---|---|---|---|
| 수신 | ProjectReleaseRequested | stove.studio.v1 | consumer group `catalog-project-release`, earliest |
| 수신 | ProjectReleaseWithdrawalRequested | stove.studio.v1 | 공개 확정과 같은 종료 경계에서 조정 |
| 발행 | ProjectReleaseCompleted / Rejected | stove.release.v1 | requestEventId는 최초 수신 요청 eventId 그대로 반환 |
| 발행 | ProjectReleaseWithdrawalResolved | stove.release.v1 | 철회/공개 선확정 결과 |
| 발행 | CatalogProductPublished | stove.catalog.v1 | 공개본·판매·노출 변경 시 같은 트랜잭션 Outbox. saleStatus·catalogVersion·listed 포함 |

Inbox 마킹·요청 최종 결과 저장·상품/현재 공개본 변경·결과 Outbox 기록을 하나의 DB 트랜잭션으로 묶고 실패 시 전부 롤백한다. 커밋 후 공통 릴레이가 at-least-once로 전달한다.

## 저장소 적용(다운로드 제한)

- `core.port.BuildDistribution`을 S3 태그 전용 어댑터가 구현한다. GetObjectTagging·PutObjectTagging만 허용하는 전용 계정(`CATALOG_DOWNLOAD_STORAGE_*`), 확인용 읽기 계정(`DOWNLOAD_STORAGE_*`, Download와 동일 읽기 전용)을 사용한다.
- 스케줄러가 실행 예정 상품을 최대 20개(기본 5초 주기) 선택, 상품 행 잠금·최신 상태 재확인 뒤 프로젝트 내 모든 COMPLETED 공개본 객체를 처리한다. `projects/{projectId}/` 키 경계를 먼저 확인하고 벗어난 자료는 FAILED로 남긴다.
- 태그 재조회 + 읽기 계정 1바이트 GET(차단 시 AccessDenied, 해제 시 성공)을 확인한 뒤에만 APPLIED. 실패는 FAILED+다음 시각(+30초) 재시도, 성공 뒤 +60초 재확인으로 수렴. 호출당 기본 5초(상한 10초).
- 새 공개본은 제한을 해제하지 않으며, 제한을 한 번이라도 쓴 상품은 공개본 변경 시 저장소 재확인을 예약한다. 원자 즉시 차단·진행 중 전송 종료·파일 회수는 보장하지 않는다.

## 외부 연동

- 입력: Studio(Kafka 출시/철회 요청), Gateway(공개 GET·판매/노출/다운로드 관리 라우팅).
- 출력: Store(CatalogProductPublished·내부 전체 목록/상세), Order(내부 주문 근거), Download(내부 빌드 조회), Settlement(소유자 내부 조회).
- 저장소: S3/MinIO 태그 쓰기(제한 전용 계정)·읽기 확인.

## 동작 요약

- 출시: 최초 공개·상위 버전 교체·하위 버전 거절·동일 버전 충돌, 불변 스냅샷, 중복/동시성 직렬화, DLT·원본 eventId 재전달.
- 공개 조회: listed=true 목록, 최종 listed=false 상세·새 주문 404, no-store, 내부 필드 미노출.
- 판매/노출: 두 조건 독립 저장·버전 대조·감사·공개 사본 Outbox, 현재 버전 무변경 명령은 변경 없음, 옛 버전 409.
- 다운로드: 202 접수·저장소 PENDING/APPLIED/FAILED 수렴, 판매 제한과 독립, License ACTIVE·상품 대조 유지.
- 철회: 공개 확정과 같은 종료 경계 조정, 선확정 결과 유지, 유예시간 없음. 기본 poll 200ms는 최소 철회 시간이 아니다.
- 판매 제한·숨김의 관측 지점은 Order가 읽는 Catalog HTTP 정본이며 Order 저장과 별도 트랜잭션이다. 제한 완료 후 시작한 조회는 반영하지만, 겹친 조회까지 원자 즉시 차단하는 분산 잠금은 없다.
- 공개 사본 필드 추가(saleStatus·catalogVersion·listed)와 내부 키 경로는 Catalog·Store·Order·Payment를 정비 구간에 함께 교체하는 전제이며 임의 혼합 버전을 지원하지 않는다.

## 사용 안내

- 공개 조회는 Gateway GET `/api/v1/products?page=0&size=20`, `/api/v1/products/by-code/{productCode}`다. 상품 생성·수정 HTTP API는 없고, 공개본은 Studio 출시 요청(`POST /api/v1/studio/projects/{id}/release-requests`, requestId·contentVersion)으로만 바뀐다. 202 이후 Catalog 공개·Studio COMPLETED 반영 사이에 비동기 지연이 있다.
- 공개 작업 설정: `stove.catalog.publication.poll-interval-ms`(200), `batch-size`(20, 1~100), `worker-enabled`(true).
- 검증: `./gradlew :apps:catalog:test :apps:catalog:integrationTest`, `python3 tools/verification/verify-catalog.py`(Auth·Gateway·Studio·Review·Catalog와 인프라만, 포트 충돌 시 `CATALOG_TEST_PORT_OFFSET`). `CATALOG_WITHDRAWAL_CHECK=true`를 주면 심사/출시 철회·동일 자료 재신청·같은 승인본 재출시·재시작 후 공개 재개도 실행한다.
- 기존 DB 전환: 쓰기 프로세스·리스너·릴레이를 멈추고 백업한 뒤 [전환 SQL](../../../tools/migrations/catalog/catalog-transition.sql)을 실행한다(구 미발행 Outbox를 `catalog_legacy_outbox_archive`에 보존하고 DEAD로 격리, 반복 실행 가능, `unexpected_legacy_pending`=0 확인). 이어 Studio `studio_release_request`와 Catalog `catalog_release`를 따로 추출해 대조하고, 빠진 요청은 원본 `ProjectReleaseRequested`를 eventId·키·헤더 그대로 재전달한다. 원본이 없으면 누락으로 기록하고 현재 작업본으로 과거 스냅샷을 추측하지 않는다. 격리한 구 Outbox를 DEAD 일괄 재큐잉으로 되살리지 않는다.
