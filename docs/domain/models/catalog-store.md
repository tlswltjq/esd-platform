# 공개 상품·진열 모델

[전체 안내](../model-guide.md) · [Catalog MVP 계획](../../catalog-mvp-plan.md)

Catalog는 Studio의 출시 요청을 처리해 공개 상품·공개본의 정본을 관리한다.
Store는 향후 이 공개 데이터를 이용해 검색·진열 사본을 구성한다. 현재 Store 구현은 재작성 전이며 아래 Store 설명은 기존 구현 기록이다.

<a id="product"></a>
## CatalogProduct — 현재 공개 상품

저장: MySQL `catalog_product`. [모델](../../../apps/catalog/src/main/java/com/stove/catalog/core/domain/CatalogProduct.java)

| 값 | 의미 |
|---|---|
| id | Catalog가 생성하는 상품 ID |
| projectId·productCode·ownerSubject | Studio의 Project와 소유자 연결. 최초 공개 후 변경 불가 |
| currentReleaseRequestId | 현재 공개 스냅샷의 출시 요청 ID |
| publishedAt·updatedAt | 최초 공개 시각과 현재 공개본 변경 시각 |

최초 출시 요청이 정상 처리될 때 생성한다. 더 높은 contentVersion을 공개해도 상품 ID와 최초 공개 시각은 유지한다.
가격·소개·빌드 참조는 연결된 CatalogRelease의 불변 스냅샷에서 읽는다.

<a id="product-status"></a>
## CatalogRelease — 출시 처리 기록과 공개 스냅샷

저장: MySQL `catalog_release`. [모델](../../../apps/catalog/src/main/java/com/stove/catalog/core/domain/CatalogRelease.java)

| 값 | 의미 |
|---|---|
| releaseRequestId·requestEventId·reviewRequestId | Studio 원본 출시 요청·이벤트·승인된 심사 연결 |
| projectId·productCode·contentVersion | 요청의 Project·상품 코드·자료 버전 |
| snapshot | 요청 당시 ProjectSnapshot 전체. 이후 변경하지 않음 |
| status·reason | COMPLETED 또는 REJECTED, 거절 시 사유 |
| requestedAt·processedAt | 원본 이벤트 발생 시각과 Catalog 처리 시각 |

COMPLETED 기록은 공개 가능한 불변 스냅샷이며 현재 상품의 참조가 공개 조회에 쓰인다.
더 낮은 버전의 새 요청은 STALE_CONTENT_VERSION으로 거절한다. 과거 공개본·요청 기록은 보존한다.
같은 버전·동일 스냅샷의 새 요청은 완료 회신하되 현재 참조·갱신 시각은 바꾸지 않는다.
출시 완료는 공개 조회 확정을 뜻하며 판매·결제·구매자 파일 배포 완료를 뜻하지 않는다.

기존 Product·Promotion·Quote 모델과 가격 견적·할인 API는 MVP에서 제거했다.
구 테이블은 역사 자료로 보존하며 신규 공개 API에서는 읽지 않는다.

## Store — 재작성 전 구현 기록

아래의 ProductChanged 소비 및 검색·캐시 구조는 향후 Store 재작성의 제약이 아니다.
Catalog MVP는 이 구 이벤트를 발행하지 않는다. 새 변경 이벤트·초기 동기화 계약은 Store 재작성 때 정한다.

## ProductDocument — 검색·진열용 사본

저장: Store의 Elasticsearch `stove-products` 인덱스. [모델](../../../apps/store/src/main/java/com/stove/store/core/domain/ProductDocument.java)

| 속성 | 뜻과 사용처 |
|---|---|
| `id` (`String`) | Catalog `productId`의 문자열. 같은 ID로 검색 문서를 갱신 |
| `productCode`, `name` (`String`), `sellerId` (`Long`) | 상품 코드·공개 상품명·판매자 |
| `price` (`Long`), `currency` (`String`) | 할인 전 정가·통화 |
| `promotionScheduleJson` (`String`) | 행사 ID·단위 할인액·부담자·기간 목록 |
| `status` (`String`), `visible` (`Boolean`) | 판매 상태와 공개 노출 여부 |
| `ratingCode` (`String`) | 승인 등급 |
| `releaseId`, `buildId`, `metadataRevision` (`Long`) | 표시하는 공개 릴리스·빌드·자료 ID |
| `productKind`, `parentProductCode`, `editionName` (`String`), `bundleProductCodes` (`List<String>`) | 상품 종류·가족 관계 |
| `storefront` (`StorefrontSnapshot`) | 고객용 소개·이미지·요구 사양 등 공개 자료 |
| `projectionVersion` (`long`) | 적용할 상품 변경의 순번 |
| `indexedAt` (`Instant`) | 검색 문서를 만든 시각 |

**만들고 쓰는 때:** Catalog의 `ProductChanged`로 검색 문서를 갱신하고 검색·진열 조회에 사용한다. 상품 ID를 고정해 갱신하며 버전을 비교해 오래된 변경을 방어한다. 원본 변경부터 사본 반영까지 지연이 가능하므로 주문의 최종 금액은 이 문서가 아니라 Catalog 견적으로 결정한다. [StoreService](../../../apps/store/src/main/java/com/stove/store/core/service/StoreService.java)

## StoreProductView — 캐시에서 읽는 진열 값

[모델](../../../apps/store/src/main/java/com/stove/store/core/domain/StoreProductView.java). 별도 상품 엔티티가 아니며 ProductDocument에서 만든 조회·캐시 값이다.

| 속성·계산 결과 | 뜻과 사용처 |
|---|---|
| `productId`, `productCode`, `name`, `sellerId` | 검색 문서의 상품 식별·설명. `id` 문자열은 숫자 `productId`로 변환 |
| `listPrice`, `currency`, `promotionScheduleJson` | 정가·통화·행사 일정. 문서의 `price`를 여기서는 `listPrice`로 부름 |
| `status`, `visible`, `purchasable`, `ratingCode` | 상태·노출·구매 가능 여부·등급 |
| `releaseId`, `buildId`, `metadataRevision` | 표시 내용의 출처 |
| `productKind`, `parentProductCode`, `editionName`, `bundleProductCodes`, `storefront` | 상품 관계·소개 자료 |
| `price()`, `discountAmount()`, `promotionId()`, `discountBearer()` | 현재 시각과 행사 일정을 대조해 계산하는 최종 단가·할인액·행사·부담자 |

행사가 시작·끝나는 순간에도 캐시에 저장한 일정으로 가격을 계산할 수 있게 만든 구조다. Catalog의 `Product.price`, Store의 `ProductDocument.price`, 이 모델의 `listPrice`는 정가이고, 이 모델의 `price()`는 할인 적용 결과다.

구 Catalog의 AuditLog·Product·견적·프로모션 모델과 테스트는 재작성으로 제거했다.
현재 Catalog 검증은 [출시 통합 테스트](../../../apps/catalog/src/integrationTest/java/com/stove/catalog/core/service/CatalogPublicationIntegrationTest.java)와
[공개 API 계약 테스트](../../../apps/catalog/src/integrationTest/java/com/stove/catalog/api/contract/CatalogOpenApiContractTest.java)를 따른다.
