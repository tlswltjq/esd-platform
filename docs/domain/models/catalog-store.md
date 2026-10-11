# 상품·할인·진열 모델

[전체 안내](../model-guide.md) · [주문으로 가격 전달](order-payment.md#order-item)

Catalog는 현재 판매할 상품과 가격의 원본을, Store는 구매 화면의 검색·진열 사본을 관리한다. 상점 화면에서 봤던 가격이 그대로 주문 가격이 되는 것은 아니다. 주문을 만들 때 Catalog가 판매 가능 여부와 그 시점의 할인을 다시 확인한다.

<a id="product"></a>
## Product — 구매 대상인 상품 원본

저장: Catalog의 MySQL `product`와 구성품 목록 `product_bundle_component`. [모델](../../../apps/catalog/src/main/java/com/stove/catalog/core/domain/Product.java)

| 속성 | 뜻과 사용처 |
|---|---|
| `id` (`Long`) | 주문·이용권·정산에서 쓰는 `productId` |
| `productCode` (`String`), `gameId` (`Long`) | Studio와 연결하는 공통 코드·프로젝트 ID |
| `currentReleaseId`, `currentBuildId`, `metadataRevision` (`Long`) | 현재 공개 릴리스·기본 빌드·상점 자료 이력 ID |
| `name` (`String`), `sellerId` (`Long`) | 공개 상품명·판매자 참조 |
| `price` (`long`), `currency` (`String`) | 할인 전 현재 정가와 통화 |
| `status` (`ProductStatus`) | 신규 구매 허용 여부. `ON_SALE`만 구매 가능 |
| `ratingCode` (`String`) | 공개된 승인 등급 |
| `productKind`, `parentProductCode`, `editionName` (`String`) | BASIC/DEMO/DLC/EDITION/BUNDLE 종류·부모 코드·에디션 이름 |
| `bundleProductCodes` (`List<String>`) | 묶음에 포함된 상품 코드 목록 |
| `storefrontSnapshotJson` (`String`) | LIVE 공개 때 받은 상점 소개 자료 JSON |
| `projectionVersion` (`long`) | Store에 전달하는 상품 변경 순번. 오래된 검색 사본 갱신을 거르는 기준 |

**만들고 쓰는 때:** `ReleasePublished`를 받으면 코드 기준으로 생성하거나 기존 상품을 갱신한다. 같은 상품 코드는 기존 `productId`를 유지한다. 공개 내용·가격·빌드 참조를 함께 반영하고 일반 상품은 `ON_SALE`, DEMO·BUNDLE은 `APPROVED`가 된다. 새 변경은 `ProductChanged`로 Store·Download에 전달한다.

<a id="product-status"></a>

| 상태·행동 | 현재 의미 |
|---|---|
| `DRAFT`, `REVIEWING`, `APPROVED` | 초안·기존 심사 진행·승인/판매 전 상태. 모든 상품이 이 순서를 거치는 것은 아님 |
| `openSale()` | 공개 릴리스가 있는 APPROVED/SUSPENDED 일반 상품을 ON_SALE로 변경 |
| `suspend()` | SUSPENDED로 변경. 이미 구매한 이용권을 회수하는 행위는 아님 |
| `CLOSED` | enum에 정의된 값. 현재 Product에 전환 메서드는 없음 |
| `applyRelease()` | 공개 정보 적용 시 ON_SALE로 설정. 기존 판매 중지 유지 정책은 [Q3](../open-questions.md#q3) |

공개 상세의 노출 조건은 릴리스가 있고 상태가 ON_SALE/APPROVED/SUSPENDED인 것이다. 따라서 “보임”과 “살 수 있음”이 다르다. [ProductCommandService](../../../apps/catalog/src/main/java/com/stove/catalog/core/service/ProductCommandService.java), [ProductQueryService](../../../apps/catalog/src/main/java/com/stove/catalog/core/service/ProductQueryService.java)

**업무 규칙 제안:** 승인된 공개 자료를 상품에 반영하고, 구매 시점의 가격과 판매 가능 여부를 서버가 판정한다. 과거 주문의 금액은 상품 변경으로 바꾸지 않는다.

**사례:** 새 LIVE 릴리스를 반영해도 같은 `productCode`의 기존 `productId`를 유지한다. 심사 승인만 받은 상품을 공개 릴리스 없이 판매 시작하는 요청은 거절된다.

**합의 상태:** 초안. 공개와 자동 판매의 정책은 [Q3](../open-questions.md#q3).

**검증 근거:** [Product](../../../apps/catalog/src/main/java/com/stove/catalog/core/domain/Product.java), [ProductCommandService](../../../apps/catalog/src/main/java/com/stove/catalog/core/service/ProductCommandService.java). [ProductTest](../../../apps/catalog/src/test/java/com/stove/catalog/core/domain/ProductTest.java)의 `reviewAloneCannotOpenSale`, [ProductCommandServiceTest](../../../apps/catalog/src/integrationTest/java/com/stove/catalog/core/service/ProductCommandServiceTest.java)의 `releaseProjectsProductFamily`, `reReviewUpdatesInPlaceAndRepublishes`.

### 판매 상태의 업무 규칙

**업무 규칙 제안:** 판매 가능한 종류와 공개 자료가 준비된 상품만 신규 주문에 허용한다. 판매 중지 뒤 재개 조건을 명시한다.

**사례:** 공개된 BASIC 상품은 주문할 수 있다. 공개된 BUNDLE도 구성품 권한 지급 경로가 없으므로 일반 주문을 받을 수 없다. 이 구현 제한만으로 “번들은 앞으로도 판매하지 않는다”는 정책을 합의한 것으로 읽지 않는다.

**합의 상태:** 재검토 필요. 자동 판매·정지 유지 [Q3](../open-questions.md#q3), 종류별 지급 정책 [Q5](../open-questions.md#q5).

**검증 근거:** [ProductStatus](../../../apps/catalog/src/main/java/com/stove/catalog/core/domain/ProductStatus.java), [Product](../../../apps/catalog/src/main/java/com/stove/catalog/core/domain/Product.java). [ProductTest](../../../apps/catalog/src/test/java/com/stove/catalog/core/domain/ProductTest.java)의 `releaseMakesProductPurchasable`, `suspendedProductIsNotPurchasable`; [ProductCommandServiceTest](../../../apps/catalog/src/integrationTest/java/com/stove/catalog/core/service/ProductCommandServiceTest.java)의 `releaseProjectsProductFamily`, `suspendedProductCanResume`. 종류·빌드 명세는 [상품 관계와 빌드](studio.md#game-project).

## Promotion — 기간이 있는 할인 행사

저장: Catalog의 MySQL `promotion`. [모델](../../../apps/catalog/src/main/java/com/stove/catalog/core/domain/Promotion.java)

| 속성 | 뜻과 사용처 |
|---|---|
| `id`, `productId`, `sellerId` (`Long`) | 행사·대상 상품·판매자 |
| `discountPerUnit` (`long`) | 상품 하나당 할인액. 할인율이 아님 |
| `bearer` (`Promotion.Bearer`) | `SELLER` 판매자 부담 또는 `PLATFORM` 플랫폼 부담 |
| `startsAt`, `endsAt` (`Instant`) | 시작은 포함, 끝은 제외하는 적용 기간 |
| `stoppedAt` (`Instant`) | 중지한 시각. 중지 후 현재 행사로 적용하지 않음 |

**만들고 쓰는 때:** 판매자가 자신의 상품에 판매자 부담 행사를 만들거나 운영자가 플랫폼 부담 행사를 만든다. 같은 상품의 기간은 겹칠 수 없고 최종 단가는 1 이상이어야 한다. 견적 시점에 유효한 행사만 적용하고 할인 부담 주체까지 주문 항목에 고정한다. [PromotionService](../../../apps/catalog/src/main/java/com/stove/catalog/core/service/PromotionService.java), [PromotionFacade](../../../apps/catalog/src/main/java/com/stove/catalog/api/application/PromotionFacade.java)

**예:** 정가 10,000원에서 2,000원을 할인하면 고객은 8,000원을 낸다. 판매자 부담이면 8,000원, 플랫폼 부담이면 10,000원을 기준으로 판매자 수수료를 계산한다. [금액 비교](settlement.md#money-example)

## Quote·QuoteItem·ProductView — 저장하지 않는 견적·조회 값

| 모델 | 속성과 사용 시점 |
|---|---|
| [QuoteItem](../../../apps/catalog/src/main/java/com/stove/catalog/core/domain/QuoteItem.java) | `productId`, `quantity`: 구매하고 싶은 상품·수량 입력 |
| [Quote](../../../apps/catalog/src/main/java/com/stove/catalog/core/domain/Quote.java) | `lines`, `totalAmount`, `currency`: 판매 상태·통화·가격·할인·수수료를 확인한 견적 결과. `lines`는 [OrderLine](order-payment.md#order-item) 목록 |
| [ProductView](../../../apps/catalog/src/main/java/com/stove/catalog/core/domain/ProductView.java) | 상품 조회·캐시 값. `productId`, `productCode`, `gameId`, `releaseId`, `buildId`, `metadataRevision`, `name`, `sellerId`, `price`, `currency`, `status`, `ratingCode`, `productKind`, `parentProductCode`, `editionName`, `bundleProductCodes`, `storefront` |
| [ReindexPage](../../../apps/catalog/src/main/java/com/stove/catalog/core/domain/ReindexPage.java) | 검색 사본 재구성 작업의 `published`, `lastId`, `hasNext`: 이번 발행 수·마지막 ID·다음 묶음 여부 |

`ProductView.purchasable()`·`visible()`는 저장 필드가 아니라 릴리스·상태로 계산하는 결과다. Catalog와 Order에 각각 있는 Quote/QuoteItem은 각 서비스의 호출 계약을 표현하며 같은 DB 행을 공유하지 않는다.

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

Catalog `AuditLog`는 [공통 감사 기록](shared.md#audit-log)에 설명했다. 기존 검증: [ProductTest](../../../apps/catalog/src/test/java/com/stove/catalog/core/domain/ProductTest.java), [ProductQuoteTest](../../../apps/catalog/src/integrationTest/java/com/stove/catalog/core/service/ProductQuoteTest.java), [PromotionPricingTest](../../../apps/catalog/src/integrationTest/java/com/stove/catalog/core/service/PromotionPricingTest.java).
