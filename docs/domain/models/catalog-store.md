# 공개 상품·진열 모델

[전체 안내](../model-guide.md) · [Catalog MVP 계획](../../catalog-mvp-plan.md)

Catalog는 Studio의 출시 요청을 처리해 공개 상품·공개본의 정본을 관리한다.
Store는 이 공개 데이터의 검색·조회 사본을 MySQL에 저장한다. [Store MVP 계획](../../store-mvp-plan.md).

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

## StoreProduct — 공개 상품 조회 사본

저장: MySQL `store_product`. [모델](../../../apps/store/src/main/java/com/stove/store/core/domain/StoreProduct.java)

| 값 | 의미 |
|---|---|
| productId·productCode | Catalog의 상품 식별자·코드. Store는 자체 ID를 발급하지 않음 |
| contentVersion | Catalog가 공개한 자료 버전. 사본 교체의 신구 판단 기준 |
| title·shortDescription·content·thumbnailUrl·videoUrl | 공개된 소개 자료 |
| price·currency | 공개 KRW 정가. 할인이나 구매 가능 여부를 계산하지 않음 |
| buildVersion | 공개 빌드의 표시 버전. 파일 경로·다운로드 권한은 소유하지 않음 |
| publishedAt·updatedAt | Catalog 최초 공개 시각·공개본 변경 시각 |

CatalogProductPublished의 공개 스냅샷과 시작 시 HTTP 상세 응답을 같은 ApplyCatalogProduct로 반영한다.
버전 비교와 저장은 하나의 트랜잭션이다. 더 높은 버전만 교체하며 같은 내용의 반복 입력과 낮은 버전은 유지한다.
같은 버전의 다른 내용이나 식별 연결 변경은 기존 사본을 보존하고 실패 처리한다.
Store의 부수효과는 사본 저장뿐이므로 별도 Inbox·Outbox를 두지 않는다.

Store가 초기화에 성공한 뒤 Catalog가 중단돼도 저장한 사본을 조회한다.
주기적 자동 대조는 없고 재시작하면 기존 목록·상세 API를 다시 읽는다.
검색 저장소·캐시·featured·프로모션을 다루던 구 모델은 제거했다.
검증과 수동 예제는 [Store 실행 안내](../../store-guide.md)를 따른다.
