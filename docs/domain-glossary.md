# 도메인 용어 위키

코드나 테스트에서 처음 만나는 말을 빠르게 찾기 위한 문서다. **도메인 용어의 뜻과 서로 다른 객체의 경계**를 설명한다. API 호출 순서는 [창작자 API 예시](p3-creator-api.md), 서비스별 책임과 엔드포인트는 [서비스 명세](services.md)를 본다.

## 빠른 찾기

| 궁금한 말 | 읽을 곳 |
|---|---|
| `workspaceId`, `gameId`, `productCode`, `productId` | [식별자와 주체](#식별자와-주체) |
| `Revision`, `revisionNo`, `entityVersion`, `productVersion` | [리비전과 여러 버전](#리비전과-여러-버전) |
| `GameBuild`, 전체 빌드, 델타, 빌드 변형 | [빌드와 업로드](#빌드와-업로드) |
| `Submission`, `ReviewCase`, `SubmissionGate` | [심사 제출과 심사](#심사-제출과-심사) |
| `Release`, `Product`, `PatchManifest`, 다운로드 티켓 | [출시와 노출](#출시와-노출) |
| `Order`, `Payment`, `License`, `Entitlement`, 정산 원장 | [구매와 이용](#구매와-이용) |

## 한눈에 보는 흐름

```text
Workspace → GameProject ─┬→ StorePageRevision · PricingRevision · RatingRevision
                         └→ GameBuild(전체/델타)
               선택한 리비전과 검증된 빌드 → Submission
               SubmissionCreated → ReviewCase → SubmissionGate → Release
               ReleasePublished → Catalog Product · Download PatchManifest
               Product → Order → Payment → License → Download Entitlement
                                          └→ SettlementRecord
```

화살표는 업무 흐름이다. 각 서비스는 자기 모델을 저장하며, 다른 서비스의 객체를 그대로 공유하지 않는다. 예를 들어 `studio`의 `Submission`과 `review`의 `SubmissionSnapshot`은 같은 제출 건을 서로 다른 서비스에서 표현한다.

## 식별자와 주체

| 용어 | 뜻과 구별할 점 | 코드 |
|---|---|---|
| **Workspace** | 창작자 계정에 연결된 작업 공간. `studio`의 프로젝트·제출·출시 접근 범위다. 구매자의 `memberId`와 다르다. | [Workspace](../apps/studio/src/main/java/com/stove/studio/core/domain/Workspace.java) |
| **GameProject** | 창작자가 등록하고 관리하는 게임 프로젝트. 하나의 프로젝트에서 여러 리비전, 빌드, 제출물, 릴리스가 생길 수 있다. | [GameProject](../apps/studio/src/main/java/com/stove/studio/core/domain/GameProject.java) |
| **`gameId`** | `studio`의 `GameProject` DB ID. 프로젝트 관련 API 경로에 쓰인다. | [GameProject](../apps/studio/src/main/java/com/stove/studio/core/domain/GameProject.java) |
| **`productCode`** | 창작자가 정하는 상품 코드. 서비스 경계를 넘을 때 같은 게임을 가리키는 자연 키다. `gameId`나 `productId`와 값이 같다는 뜻은 아니다. | [GameProject](../apps/studio/src/main/java/com/stove/studio/core/domain/GameProject.java) |
| **`productId`** | `catalog`의 `Product` DB ID. 주문 항목과 라이선스는 이 ID로 상품을 참조한다. | [Product](../apps/catalog/src/main/java/com/stove/catalog/core/domain/Product.java) |
| **`sellerId` / `memberId`** | 각각 판매자와 구매자 식별자다. `studio`의 `GameProject.sellerId`는 개인 Workspace를 가리키는 기존 필드명이다. | [GameProject](../apps/studio/src/main/java/com/stove/studio/core/domain/GameProject.java), [Order](../apps/order/src/main/java/com/stove/order/core/domain/Order.java) |
| **상품 종류 (`ProductKind`)** | `BASIC`, `DEMO`, `DLC`, `EDITION`, `BUNDLE`로 프로젝트의 상품 관계를 나타낸다. 번들 구성이나 상위 게임 관계는 [상품 관계 문서](p2-product-family-and-builds.md#상품-관계)를 본다. | [ProductKind](../apps/studio/src/main/java/com/stove/studio/core/domain/ProductKind.java) |

## 리비전과 여러 버전

**리비전(Revision)**은 프로젝트의 심사 대상 자료를 종류별로 기록한 이력이다. `studio`에는 상점 페이지, 가격, 등급 리비전이 따로 있다. 제출할 때 세 리비전의 **ID를 각각 선택**하므로, 상점 문구만 고치면 새 상점 리비전을 선택해 새 제출물을 만들 수 있다. 기존 제출물이 가리키는 ID는 바뀌지 않는다.

제출 API의 `metadataRevisionId`는 `StorePageRevision.id`, `pricingRevisionId`는 `PricingRevision.id`, `ratingRevisionId`는 `RatingRevision.id`를 가리킨다. 이벤트의 `metadataRevision`·`pricingRevision`·`ratingRevision`도 이 ID를 뜻한다. 셋 모두 `revisionNo`가 아닌 **DB ID**다. 상점 리비전에도 `pricesJson` 필드가 있지만, 출시 이벤트의 대표 `price`·`currency`는 가격 리비전에서 읽는다.

| 용어 | 뜻과 구별할 점 | 코드 |
|---|---|---|
| **StorePageRevision** | 제목·설명·이미지 등 상점 페이지 자료. `DRAFT`는 수정·미리보기가 가능하고, `PUBLISHED`가 되면 내용을 수정할 수 없으며 심사 제출에 사용할 수 있다. 여기서의 발행은 **상점 공개가 아니라 리비전 확정**이다. | [StorePageRevision](../apps/studio/src/main/java/com/stove/studio/core/domain/StorePageRevision.java) |
| **PricingRevision** | 국가·통화·가격의 한 이력. 출시 이벤트의 대표 가격·통화는 이 리비전에서 가져온다. 생성된 리비전을 직접 수정하는 메서드는 없다. | [PricingRevision](../apps/studio/src/main/java/com/stove/studio/core/domain/PricingRevision.java) |
| **RatingRevision** | 등급 설문, 대상 국가, 정책 버전, 정책이 결정한 권장 등급과 등급 경로의 한 이력. 실제 심사 승인 결과인 `ratingCode`와 구별한다. | [RatingRevision](../apps/studio/src/main/java/com/stove/studio/core/domain/RatingRevision.java) |
| **`revisionId` / `revisionNo`** | `id`는 특정 리비전 행을 가리키며 제출 API가 받는 값이다. `revisionNo`는 **같은 프로젝트·같은 종류 안에서** 증가하는 이력 번호다. | [RevisionService](../apps/studio/src/main/java/com/stove/studio/core/service/RevisionService.java) |
| **`policyVersion`** | 등급 분류 규칙의 버전. 리비전 생성 때 기록하고 제출 시 현재 유효한 정책인지 다시 검사한다. 게임 파일 버전과 무관하다. | [KoreanRatingPolicy](../apps/studio/src/main/java/com/stove/studio/core/domain/KoreanRatingPolicy.java) |
| **`productVersion` / `GameBuild.version`** | 이용자에게 제공할 게임 빌드의 **대상 버전**. 한 제출물의 모든 빌드는 이 값이 같아야 한다. | [GameBuild](../apps/studio/src/main/java/com/stove/studio/core/domain/GameBuild.java) |
| **`buildNumber`** | 업로드된 빌드에 붙이는 별도 식별 문자열. 대상 버전이나 리비전 번호와 같은 개념이 아니다. | [GameBuild](../apps/studio/src/main/java/com/stove/studio/core/domain/GameBuild.java) |
| **`entityVersion`** | JPA 낙관적 잠금용 행 버전(`@Version`). 업무 이력인 `revisionNo`, 게임 버전인 `productVersion`과 무관하다. | [Submission](../apps/studio/src/main/java/com/stove/studio/core/domain/Submission.java) |
| **`projectionVersion`** | `catalog` 상품 변경을 `store` 검색 문서에 반영할 때 오래된 이벤트를 거르는 순서 번호. 리비전 번호나 게임 버전이 아니다. | [Product](../apps/catalog/src/main/java/com/stove/catalog/core/domain/Product.java), [StoreService](../apps/store/src/main/java/com/stove/store/core/service/StoreService.java) |

예를 들어 `StorePageRevision.revisionNo=3`, `PricingRevision.revisionNo=2`, `GameBuild.version="2.0"`은 한 제출물에 함께 들어갈 수 있다. 세 숫자는 서로 맞출 필요가 없다.

## 빌드와 업로드

| 용어 | 뜻과 구별할 점 | 코드 |
|---|---|---|
| **GameBuild** | 업로드한 게임 파일의 메타데이터와 검증 상태. 실제 파일은 오브젝트 스토리지에 있다. `VALIDATED`는 업로드 파일 검증 완료이며 심사 승인이나 출시를 뜻하지 않는다. | [GameBuild](../apps/studio/src/main/java/com/stove/studio/core/domain/GameBuild.java) |
| **UploadSession** | 큰 파일을 여러 부분으로 업로드하기 위해 연 세션. `GameBuild`와 연결되지만 출시 단위는 아니다. | [UploadSession](../apps/studio/src/main/java/com/stove/studio/core/domain/UploadSession.java) |
| **전체 빌드** | 특정 `platform`(OS)·`architecture`(아키텍처)에서 새로 설치할 수 있는 파일. `deltaFromVersion == null`이다. | [SubmissionService](../apps/studio/src/main/java/com/stove/studio/core/service/SubmissionService.java) |
| **델타 빌드** | `deltaFromVersion`에 적힌 이전 버전에서 `GameBuild.version`의 대상 버전으로 갱신하기 위한 파일. 이 서비스는 델타 파일을 생성하거나 클라이언트에 적용하지 않는다. 내용상 호환성은 빌드 QA에서 확인해야 한다. | [GameBuild](../apps/studio/src/main/java/com/stove/studio/core/domain/GameBuild.java), [상품·빌드 문서](p2-product-family-and-builds.md#빌드-변형과-델타) |
| **빌드 변형 (`BuildVariant`)** | 출시에서 선택 가능한 OS·아키텍처별 전체/델타 파일의 정보. 다운로드 요청의 `platform`, `architecture`, `fromVersion`으로 고른다. | [BuildVariant](../common/event/src/main/java/com/stove/common/event/payload/BuildVariant.java) |
| **기본 빌드 (`buildId`)** | 제출 요청의 첫 빌드이며 반드시 전체 빌드다. `additionalBuildIds`에 다른 대상의 전체 빌드와 델타 빌드를 더한다. | [SubmissionService](../apps/studio/src/main/java/com/stove/studio/core/service/SubmissionService.java) |

**예시 — Windows `X86_64`용 2.0 제출:**

| 파일 | OS·아키텍처 | `version` | `deltaFromVersion` | 역할 |
|---|---|---|---|---|
| Windows 전체 파일 | `WINDOWS`·`X86_64` | `2.0` | `null` | 기본 파일과 델타의 폴백 |
| Windows 델타 파일 | `WINDOWS`·`X86_64` | `2.0` | `1.0` | 1.0 이용자의 2.0 갱신 파일 |
| macOS 전체 파일 | `MACOS`·`ARM64` | `2.0` | `null` | 다른 OS용 전체 파일 |

한 제출물은 **같은 프로젝트의 검증된 빌드**만 담고, 대상 버전도 모두 같아야 한다. 같은 OS·아키텍처·패치 기준을 중복할 수 없으며, 델타가 있다면 같은 OS·아키텍처의 전체 빌드가 함께 있어야 한다. 다운로드 시 일치하는 `fromVersion`의 델타가 없으면 해당 전체 빌드를 고른다. [SubmissionBuildSetTest](../apps/studio/src/test/java/com/stove/studio/core/service/SubmissionBuildSetTest.java)는 `Submission` 객체를 만들지 않고, 제출 전 `validateBuildSet` 규칙을 검증한다.

## 심사 제출과 심사

| 용어 | 뜻과 구별할 점 | 코드 |
|---|---|---|
| **Submission(심사 제출물)** | 상점·가격·등급 리비전 ID와 기본 빌드 ID를 고정한 **심사 신청 단위**. 선택한 자료는 바꾸지 않고, 심사 결과에 따라 제출물의 상태와 확정 등급 정보는 바뀔 수 있다. | [Submission](../apps/studio/src/main/java/com/stove/studio/core/domain/Submission.java) |
| **SubmissionBuild** | 제출물에 포함된 모든 빌드와 제출물의 연결. 빌드 묶음은 제출 후 변경하지 않는다. | [SubmissionBuild](../apps/studio/src/main/java/com/stove/studio/core/domain/SubmissionBuild.java) |
| **SubmissionSnapshot** | `SubmissionCreated` 이벤트를 받아 `review`가 보관하는 심사용 사본. `studio`의 `Submission` 행 자체가 아니다. | [SubmissionSnapshot](../apps/review/src/main/java/com/stove/review/core/domain/SubmissionSnapshot.java) |
| **ReviewCase(심사 안건)** | 제출물의 심사 유형별 안건. `RATING`, `STORE_PAGE`, `BUILD_QA`, `LEGAL`, `SDK_COMPLIANCE`, `COMMERCIAL` 각각을 심사한다. | [ReviewCase](../apps/review/src/main/java/com/stove/review/core/domain/ReviewCase.java), [ReviewType](../apps/review/src/main/java/com/stove/review/core/domain/ReviewType.java) |
| **SubmissionGate(승인 관문)** | `studio`가 제출물마다 보관하는 심사 유형별 상태. `review`의 결정 이벤트를 반영하며 모든 관문이 승인되면 제출물이 `READY_FOR_RELEASE`가 된다. `ReviewCase`와는 별도 서비스의 객체다. | [SubmissionGate](../apps/studio/src/main/java/com/stove/studio/core/domain/SubmissionGate.java), [SubmissionReviewProjectionService](../apps/studio/src/main/java/com/stove/studio/core/service/SubmissionReviewProjectionService.java) |
| **등급 경로 (`RatingPath`)** | 정책이 고른 자체등급분류 또는 외부 심사 경로. 권장 등급과 별도로 실제 승인 결과 및 증빙을 심사에서 확정한다. | [RatingPath](../apps/studio/src/main/java/com/stove/studio/core/domain/RatingPath.java), [ReviewCase](../apps/review/src/main/java/com/stove/review/core/domain/ReviewCase.java) |

수정 요청을 받으면 새 리비전으로 **새 Submission을 제출**하는 흐름이 기본이다. `Submission.sequenceNo`는 프로젝트 안에서 몇 번째 제출인지를 나타내며 리비전 번호와 별개다. `Submission.status`의 `SUBMITTED`, `CHANGES_REQUESTED`, `READY_FOR_RELEASE`, `RELEASED`는 제출물 전체의 상태이고, `ReviewCase.status`와 `SubmissionGate.status`는 각 심사 유형의 상태다. [창작자 API 예시](p3-creator-api.md#revision-심사-출시)에 실제 순서가 있다.

기존 `GameProject.submit()` → `GameRegistered` 경로도 남아 있다. 여기서 `GameProject.status=SUBMITTED`는 프로젝트 단위의 이전 심의 흐름이고, 위의 **리비전 기반 `SubmissionCreated` 흐름**과 다른 상태다. 코드를 읽을 때 두 `submit`을 같은 객체의 전이로 해석하지 않는다.

## 출시와 노출

| 용어 | 뜻과 구별할 점 | 코드 |
|---|---|---|
| **Release(릴리스)** | 승인된 Submission을 채널에 공개하거나 예약하는 출시 이력. 공개 직전에 제출물의 모든 빌드를 smoke test한다. `DEV`·`TEST`·`STAGE`·`LIVE` 채널이 있으며 `LIVE` 공개 때 상품·다운로드 쪽에 릴리스 이벤트가 전달된다. | [Release](../apps/studio/src/main/java/com/stove/studio/core/domain/Release.java), [ReleaseService](../apps/studio/src/main/java/com/stove/studio/core/service/ReleaseService.java) |
| **릴리스 변경 유형** | 이전 릴리스가 없으면 `INITIAL`, 상점·가격·등급 리비전 중 하나라도 바뀌면 `MATERIAL_CHANGE`, 그렇지 않으면 `NORMAL_PATCH`로 분류한다. `NORMAL_PATCH`는 **델타 파일을 썼다는 뜻이 아니다**. `ROLLBACK`은 이전 공개 릴리스로 되돌리는 새 릴리스다. | [ReleaseService](../apps/studio/src/main/java/com/stove/studio/core/service/ReleaseService.java), [ReleaseChangeType](../apps/studio/src/main/java/com/stove/studio/core/domain/ReleaseChangeType.java) |
| **Product(상품 마스터)** | `catalog`가 관리하는 판매 상품. 현재 공개 릴리스의 정보와 가격·판매 상태를 담는다. 릴리스 `PUBLISHED`와 상품 `ON_SALE`은 다른 상태다. | [Product](../apps/catalog/src/main/java/com/stove/catalog/core/domain/Product.java) |
| **ProductDocument(검색 문서)** | `store`가 상품 변경 이벤트로 갱신하는 검색·진열용 읽기 모델. 상품 마스터와 별도의 저장소에 있다. | [ProductDocument](../apps/store/src/main/java/com/stove/store/core/domain/ProductDocument.java) |
| **PatchManifest(패치 매니페스트)** | `download`가 공개 릴리스마다 보관하는 빌드 목록. 업로드 완료만으로 생기지 않고 `ReleasePublished`를 받아 만든다. | [PatchManifest](../apps/download/src/main/java/com/stove/download/core/domain/PatchManifest.java) |
| **DownloadTicket(다운로드 티켓)** | 보유권 확인 후 발급하는 특정 빌드의 다운로드 정보와 만료되는 서명 URL. 델타를 찾지 못하면 같은 OS·아키텍처의 전체 빌드를 선택한다. | [DownloadTicketService](../apps/download/src/main/java/com/stove/download/core/service/DownloadTicketService.java) |

`StorePageRevision.PUBLISHED`는 심사에 사용할 **상점 자료 확정**, `Release.PUBLISHED`는 **릴리스 공개**, `Product.ON_SALE`은 **구매 가능**을 뜻한다. 같은 단어 `PUBLISHED`라도 객체가 다르면 단계가 다르다.

## 구매와 이용

| 용어 | 뜻과 구별할 점 | 코드 |
|---|---|---|
| **Order / OrderItem** | 구매 요청과 항목. 서버가 상품 가격을 확인해 주문 금액을 계산하고 `orderNo`로 이후 결제·지급·정산을 연결한다. | [Order](../apps/order/src/main/java/com/stove/order/core/domain/Order.java), [OrderItem](../apps/order/src/main/java/com/stove/order/core/domain/OrderItem.java) |
| **Payment** | 주문번호에 연결된 결제. 주문 금액을 사전등록하고 PG 승인 금액과 대조한다. 결제 실패와 승인 뒤 취소·환불은 다른 전이다. | [Payment](../apps/payment/src/main/java/com/stove/payment/core/domain/Payment.java) |
| **License(라이선스)** | 결제 완료 후 회원에게 지급한 상품 소유권의 원본 기록. 한 주문·상품당 한 건을 보장한다. | [License](../apps/license/src/main/java/com/stove/license/core/domain/License.java) |
| **Entitlement(보유권 사본)** | `download`가 라이선스 이벤트로 유지하는 접근 판정용 사본. 다운로드 요청은 이를 조회하므로 `license`에 동기 호출하지 않는다. | [Entitlement](../apps/download/src/main/java/com/stove/download/core/domain/Entitlement.java) |
| **SettlementRecord(정산 원장)** | 주문 항목별 매출 `SALE` 또는 환불 `REFUND` 기록. 환불은 원 매출의 금액을 반대 부호로 기록한다. `settlementMonth`는 정산 귀속 월이다. | [SettlementRecord](../apps/settlement/src/main/java/com/stove/settlement/core/domain/SettlementRecord.java) |

이 구간의 가격은 주문 시점에 확인한 값을 이후 이벤트와 원장에 전달한다. 현재 상품 가격이 바뀌어도 기존 결제·정산의 기준 금액을 소급해 바꾸지 않는다. 자세한 금액 계산과 할인 부담 규칙은 [서비스 명세의 주문·정산 절](services.md#order)을 본다.

## 용어를 추가할 때

새 용어에는 **뜻, 소유 서비스, 비슷한 용어와의 차이, 코드 위치**를 함께 적는다. 상태나 숫자 규칙은 코드가 바뀔 수 있으므로 해당 클래스·서비스·테스트 링크를 갱신한다. HTTP 예제나 운영 절차는 이 문서에 복제하지 않고 기존 상세 문서로 연결한다.
