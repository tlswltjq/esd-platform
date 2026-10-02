# 도메인 용어집

창작자가 게임을 등록해 출시하고, 구매자가 결제해 이용하며, 운영자가 판매자별 금액을 마감하는 업무의 언어를 정리한다. 같은 단어라도 어느 업무에서 쓰이는지 함께 읽는다.
요구사항·문서·코드·테스트에서 같은 의미로 사용할 유비쿼터스 언어의 초안이다.

검토 기준: 2026-10-02, 코드 `a525243` (`main`). [이슈 #89](https://github.com/tlswltjq/esd-platform/issues/89)의 기존 용어 위키와 상세 정의를 함께 정리했다. 식별자·리비전·빌드·읽기 모델 설명을 유지하고, 할인·정산과 창작자 API의 현재 구현을 대조했다.

## 읽는 순서와 합의 상태

1. [대표 업무 흐름](domain/workflows.md)에서 행위자와 업무 순서를 읽는다.
2. 아래 색인에서 용어를 찾아 [창작·심사·출시](domain/publishing.md), [구매·이용·정산](domain/commerce.md)의 상세 정의로 이동한다.
3. [미결 질문과 검토 기록](domain/open-questions.md)에서 정책 결정이 필요한 부분을 확인한다.

각 정의의 **업무 규칙 제안**은 담당자가 검토할 문장이고, **현재 구현**은 코드에서 확인한 동작이다. 기존 테스트 링크는 그 동작을 검증하는 근거이며 업무 합의의 증거를 대신하지 않는다.

| 합의 상태 | 의미 |
|---|---|
| 초안 | 용어·규칙을 제안했으며 담당자의 검토를 기다림 |
| 합의됨 | 검토자·검토일·결정 근거를 기록하고 관련 정의에 반영함 |
| 재검토 필요 | 구현·기존 설명·제안 규칙 사이에 차이가 있어 후속 결정이 필요함 |

이번 작성에서 새로 합의됨으로 표시한 업무 규칙은 없다. [검토 기록](domain/open-questions.md#review-log)에 실제 검토 결과를 추가한 뒤 상태를 바꾼다.

## 핵심 용어 색인

상세 정의 15개다. 영어명은 코드와 대화에서 대상을 찾기 위한 표준명 제안이다.

| 표준 한국어명 | 영어명 / 코드 | 핵심 정의 | 상세 |
|---|---|---|---|
| 게임 프로젝트 | Game Project / `GameProject` | 창작자가 자료·빌드·상품 관계를 관리하는 제작·출시 단위 | [정의](domain/publishing.md#game-project) |
| 상품 | Product / `Product` | 가격과 판매 가능 여부를 책임지는 구매 대상 | [정의](domain/publishing.md#product) |
| 상점 자료 이력 | Store Page Revision / `StorePageRevision` | 특정 시점의 소개·이미지·정책 등 상점 자료 묶음 | [정의](domain/publishing.md#store-page-revision) |
| 게임 빌드 | Game Build / `GameBuild` | 특정 버전·실행 환경에 제공할 파일 산출물과 검증 기록 | [정의](domain/publishing.md#game-build) |
| 심사 제출물 | Submission / `Submission` | 창작자가 특정 자료와 빌드 묶음에 대해 심사를 요청한 단위 | [정의](domain/publishing.md#submission) |
| 심사 사건 | Review Case / `ReviewCase` | 한 제출물의 한 심사 유형에 대한 판단·증빙·처리 이력 | [정의](domain/publishing.md#review-case) |
| 출시 관문 | Submission Gate / `SubmissionGate` | 제출물의 유형별 승인 여부를 Studio에서 확인하는 기록 | [정의](domain/publishing.md#submission-gate) |
| 릴리스 | Release / `Release` | 승인된 자료·빌드 묶음을 특정 채널에 공개하는 단위 | [정의](domain/publishing.md#release) |
| 상품 판매 상태 | Product Status / `ProductStatus` | 상품에 대한 신규 주문을 허용하는지 나타내는 상태 | [정의](domain/publishing.md#product-status) |
| 주문 | Order / `Order` | 구매자·상품 항목·구매 금액을 고정한 구매 요청 | [정의](domain/commerce.md#order) |
| 결제 | Payment / `Payment` | 주문 대금의 사전등록·승인·거절·환불 처리 기록 | [정의](domain/commerce.md#payment) |
| 이용권 | License / `License` | 회원에게 상품 이용 권한을 지급·회수한 원본 기록 | [정의](domain/commerce.md#license) |
| 다운로드 보유권 사본 | Entitlement / `Entitlement` | 다운로드 접근 판정을 위해 이용권 이벤트를 반영한 사본 | [정의](domain/commerce.md#entitlement) |
| 정산 원장 항목 | Settlement Record / `SettlementRecord` | 주문 상품별 매출 또는 환불과 수수료를 기록한 한 줄 | [정의](domain/commerce.md#settlement-record) |
| 판매자 월 마감 | Seller Settlement / `SellerSettlement` | 판매자와 귀속 월별 원장 합계를 모은 마감 결과 | [정의](domain/commerce.md#seller-settlement) |

## 의미가 유지되는 업무 범위

아래는 바운디드 컨텍스트 **후보**다. 배포 서비스 목록을 그대로 컨텍스트로 확정하지 않는다. 한 서비스 안에도 서로 다른 업무가 있고, 한 업무의 읽기 모델은 다른 서비스에 있을 수 있다. 경계 확정은 [Q1](domain/open-questions.md#q1)의 검토 대상이다.

| 업무 범위 후보 | 책임지는 의미 | 현재 담당 서비스 | 경계를 넘을 때의 관계 |
|---|---|---|---|
| 창작·출시 관리 | 프로젝트, 자료 확정, 제출물, 빌드, 릴리스 | studio | 심사에는 제출 스냅샷을 전달하고, 판매·배포에는 LIVE 릴리스 스냅샷을 전달 |
| 심사 운영 | 유형별 심사 사건, 판단, 증빙, 재검토 | review | 심사 결과를 Studio의 출시 관문에 반영. 사건의 전체 운영 상태를 복제하지는 않음 |
| 상품·진열 | 상품 가격·판매 상태, 구매자에게 보이는 상품 정보 | catalog, store | Catalog가 원본을 소유하고 Store가 검색·진열 사본을 유지. 창작 프로젝트와는 `productCode`로 연결 |
| 구매 계약 | 구매자와 주문 항목·금액·주문 상태 | order | Catalog 견적을 주문에 고정하고 Payment에 결제를 요청 |
| 대금 처리 | 결제 승인·거절·환불 | payment | 결제 완료 사실을 주문·이용권·정산이 각자의 의미로 반영 |
| 이용 권한 | 상품 이용권 원본과 회수 이력 | license | Download가 이용권 이벤트로 보유권 사본을 유지 |
| 파일 배포 | 공개 파일·변형 선택, 접근 판정, 다운로드 티켓 | download | 릴리스·상품 참조·보유권 사본을 조합해 접근 판정 |
| 판매자 정산 | 매출·환불 원장, 수수료, 월 마감 | settlement | 결제 항목을 원장으로 번역. 상품의 현재 가격으로 과거 매출을 다시 계산하지 않음 |

Auth는 회원·역할의 출처이고, Studio의 워크스페이스는 프로젝트 소유 범위다. 회원·판매자·워크스페이스는 이름만 바꾼 하나의 개념으로 취급하지 않는다. 현재 개인 워크스페이스 ID가 커머스의 `sellerId`로 전달되는 관계는 [Q8](domain/open-questions.md#q8)에 남긴다. 인증 계약은 [커머스 인증 문서](p3-commerce-security.md)를 참고한다.

## 혼동하기 쉬운 표현

<a id="publication-boundaries"></a>
### 확정·공개·판매 시작

| 권장 표현 | 코드 상태 / 행위 | 업무상 결과 |
|---|---|---|
| 상점 자료를 **확정한다** | `StorePageRevision.PUBLISHED` / `publish()` | 자료 내용을 고정해 심사 제출에 사용할 수 있음. 상점 노출 시점은 아님 |
| 릴리스를 **공개한다** | `Release.PUBLISHED` / `publish()` | 해당 채널의 자료·빌드 묶음 공개. LIVE일 때 Catalog·Download로 공개 이벤트 전달 |
| 상품의 **판매를 시작한다** | `Product.ON_SALE` / `openSale()` 또는 릴리스 반영 | 신규 주문 허용. 현재 일반 상품은 LIVE 릴리스 반영으로 자동 판매 시작 |

“발행했다”만으로는 어떤 결과인지 알 수 없다. 코드/API의 `publish`나 기존 문서의 “발행”을 인용할 때도 대상과 채널을 쓴다. 예: “상점 자료 3차를 확정했지만 릴리스는 아직 예약 상태다.” DEMO·BUNDLE의 공개와 판매 제한은 [상품 판매 상태](domain/publishing.md#product-status)를 참고한다.

<a id="versions"></a>
### 여러 버전과 이력 번호

| 표기 | 식별하는 것 | 혼동하면 생기는 문제 |
|---|---|---|
| `StorePageRevision.revisionNo` 등 | 프로젝트 안에서 해당 자료 종류의 이력 순번 | 상점 3차와 가격 3차는 각각 별도 이력 |
| `metadataRevisionId`, `pricingRevisionId`, `ratingRevisionId` | 저장된 자료 이력의 ID | `revisionNo` 대신 ID를 제출 API에 전달해야 함 |
| 이벤트·Catalog의 `metadataRevision` | 현재 구현에서는 상점 자료의 **ID** | 이름에 `Id`가 없어 순번으로 오해할 수 있음. [Q7](domain/open-questions.md#q7) |
| `GameBuild.version` / 업로드 요청 `productVersion` | 게임 제품 버전 문자열, 예: `1.2.0` | 자료 이력 순번이나 빌드 ID가 아님 |
| `buildNumber` | 빌드 실행·산출물 추적 번호 | 같은 제품 버전의 여러 OS·아키텍처 빌드를 구별할 추가 정보 |
| `deltaFromVersion` | 델타 파일 적용의 출발 제품 버전 | 대상 버전은 해당 빌드의 `version` |
| `Submission.sequenceNo`, `ReviewCase.reviewRound` | 프로젝트의 제출 차수 / 같은 심사 사건의 재검토 회차 | 새 자료로 재제출하는 것과 같은 자료를 재검토하는 것은 다름 |
| `entityVersion` (`@Version`) | 동시 수정 충돌 감지용 값 | 사용자가 출시할 게임 버전으로 지정하는 값이 아님 |
| `projectionVersion` | Catalog 상품 변경을 Store 읽기 모델에 반영할 순서 번호 | 오래된 이벤트가 새 진열을 덮어쓰지 않도록 비교하며 자료 이력·게임 버전과 별개 |
| `ratingPolicyVersion` | 등급 판정 규칙의 판본 | 게임 버전과 별개이며 현재 활성 정책과 맞아야 제출 가능 |

근거: [SubmissionService](../apps/studio/src/main/java/com/stove/studio/core/service/SubmissionService.java), [ReleaseService](../apps/studio/src/main/java/com/stove/studio/core/service/ReleaseService.java), [ReviewCase](../apps/review/src/main/java/com/stove/review/core/domain/ReviewCase.java).

<a id="identifiers"></a>
### 주요 식별자

| 식별자 | 식별 대상·발급/관리 주체 | 사용하는 범위 |
|---|---|---|
| `gameId` | Studio의 게임 프로젝트 ID | 자료·빌드·제출물·프로젝트 관계. Catalog에도 연결 참조로 보관 |
| `productCode` | 창작자가 지정하고 Studio에서 중복 검사하는 상품 코드 | Studio→Catalog→Store/Download의 공통 연결 값. Download URL에서도 사용 |
| `productId` | Catalog의 상품 ID | 주문 항목·이용권·정산 원장, Download 보유권 조회 |
| `submissionId` | Studio의 심사 제출물 ID | Review 스냅샷·사건과 Studio 관문의 연결 |
| `releaseId`, `buildId` | Studio의 공개 단위 / 파일 산출물 ID | 상품의 현재 릴리스와 Download 매니페스트 일치 확인 |
| `orderNo` | Order가 발급하는 주문번호 | 결제·이용권·정산을 연결. 각 서비스의 DB 기본 키와 별개 |
| `memberId` | Auth 회원 ID | 구매자와 이용권 수혜자. 사용자 입력 대신 인증 정보에서 결정 |
| `workspaceId` → `sellerId` | 현재 Studio 개인 워크스페이스 → 판매 주체 참조 | 프로젝트 소유권과 커머스·정산 연결. [Q8](domain/open-questions.md#q8) |

예: `gameId=7`, `productCode=GAME-A`, `productId=42`는 서로 다른 식별자다. 주문에는 `42`를, 다운로드 요청에는 `GAME-A`를 쓴다. [ProductRef](../apps/download/src/main/java/com/stove/download/core/domain/ProductRef.java)가 코드와 상품 ID·릴리스 ID의 관계를 보관한다.

<a id="source-and-copy"></a>
### 이용권 원본과 보유권 사본

License는 `(orderNo, productId)`별 지급·회수 원본이고, Entitlement는 `(memberId, productId)`별 다운로드 판정 사본이다. “라이선스 지급 완료” 직후라도 사본 반영 전에는 다운로드가 거절될 수 있다. Download는 요청마다 License에 동기 조회하지 않는다. 환불 후 재구매, 이벤트 지연의 경계는 [Entitlement](domain/commerce.md#entitlement)와 [Q5](domain/open-questions.md#q5)를 참고한다.

## 짧은 용어와 권장 동사

| 용어·표현 | 뜻과 사용 예 |
|---|---|
| 워크스페이스 / Workspace | 창작자 계정의 작업 공간이며 프로젝트·제출·출시 접근 범위. 구매자의 회원 ID와 구별. [Workspace](../apps/studio/src/main/java/com/stove/studio/core/domain/Workspace.java) |
| 상품 종류 / Product Kind | BASIC·DEMO·DLC·EDITION·BUNDLE의 상품 관계. [종류별 조건](domain/publishing.md#game-project) |
| 업로드 세션 / Upload Session | 큰 빌드 파일의 부분 업로드를 관리하는 세션. 빌드·제출·출시 단위와 별개. [UploadSession](../apps/studio/src/main/java/com/stove/studio/core/domain/UploadSession.java) |
| 가격 이력 / Pricing Revision | 심사·출시에 사용할 가격의 특정 판본. 현재 신규 생성은 KR/KRW |
| 등급 자료 이력 / Rating Revision | 설문·지역·정책 버전·판정 경로를 고정한 자료 |
| 빌드 변형 / Build Variant | 같은 대상 제품 버전의 OS·아키텍처·전체/델타 조합. [BuildVariant](../common/event/src/main/java/com/stove/common/event/payload/BuildVariant.java) |
| 기본 빌드 / `buildId` | 제출 요청의 첫 빌드이며 전체 파일이어야 함. `additionalBuildIds`로 다른 대상의 전체·델타 파일을 추가 |
| 심사 스냅샷 / Submission Snapshot | Review가 SubmissionCreated를 받아 보관하는 심사용 사본. Studio의 제출물 행과 별개. [SubmissionSnapshot](../apps/review/src/main/java/com/stove/review/core/domain/SubmissionSnapshot.java) |
| 상점 공개 사본 / Storefront Snapshot | LIVE 릴리스의 소개·이미지·요구 사양을 전달하는 사본. [StorefrontSnapshot](../common/event/src/main/java/com/stove/common/event/payload/StorefrontSnapshot.java) |
| 검색 문서 / Product Document | Store의 검색·진열용 읽기 모델. 상품 마스터와 별도 저장소에 있고 `projectionVersion`으로 오래된 변경을 걸러냄. [ProductDocument](../apps/store/src/main/java/com/stove/store/core/domain/ProductDocument.java) |
| 패치 매니페스트 / Patch Manifest | Download가 릴리스에서 투영한 배포 파일 정보 |
| 다운로드 티켓 / Download Ticket | 권한 확인 뒤 발급하는 만료 시각이 있는 서명 URL과 파일 정보 |
| 등록한다 / register | “프로젝트를 등록한다”, “빌드를 등록한다”처럼 대상 명시 |
| 확정한다 / finalize | “상점 자료를 확정한다.” 이후 변경은 새 이력으로 작성 |
| 제출한다 / submit | “확정 자료와 검증된 빌드를 묶어 심사를 요청한다.” |
| 승인한다 / approve | “BUILD_QA 심사 사건을 승인한다.” 모든 심사 완료와 구별 |
| 재제출한다 / resubmit | 수정한 자료·빌드로 새로운 Submission을 만든다 |
| 재검토한다 / appeal | 기존 제출 스냅샷에 대한 사건을 다시 연다 |
| 공개한다 / publish | “LIVE 릴리스를 공개한다.” 채널 명시 |
| 지급한다·회수한다 / issue·revoke | 이용권 원본의 생성·효력 종료. 보유권 사본에는 “반영한다” 사용 |
| 환불한다 / refund | 승인된 대금을 되돌린다. 승인 거절은 “결제 실패”, 주문 종료는 “주문 취소” |
| 마감한다 / close | 판매자·귀속 월의 미마감 원장을 합산한다. 실제 송금 완료를 뜻하지 않음 |

### 빌드 변형을 선택하는 예

Windows `X86_64`용 2.0 전체 파일(`deltaFromVersion=null`), 같은 환경의 1.0→2.0 델타 파일, macOS `ARM64`용 2.0 전체 파일을 한 제출물에 담을 수 있다. 제품 버전은 모두 같고, 델타마다 같은 OS·아키텍처의 전체 파일이 있어야 한다. 리비전 순번은 서로 맞출 필요가 없으므로 상점 자료 3차·가격 2차·제품 버전 2.0을 함께 선택할 수 있다.

다운로드 요청에 맞는 델타가 없으면 같은 OS·아키텍처의 전체 파일을 선택한다. 서비스가 델타를 생성하거나 클라이언트에 적용하는 것은 아니다. [빌드 정의와 검증 범위](domain/publishing.md#game-build), [빌드 변형·델타 명세](p2-product-family-and-builds.md#빌드-변형과-델타)를 참고한다.

## 유지 관리

개념·정책·상태 전이를 바꾸는 PR에서는 다음을 함께 확인한다.

- 바뀐 표준명·정의·정상/거절 사례와 관련 업무 흐름을 갱신한다. 명사뿐 아니라 허용 행위와 전이 조건도 확인한다.
- 코드와 기존 테스트 링크를 갱신한다. 테스트가 없는 규칙은 근거 범위를 밝히고 미결 질문에 검증 공백을 기록한다.
- 의도와 구현이 다르면 [미결 질문](domain/open-questions.md)에 현상·결정할 내용·후속 작업을 남긴다. 문서만으로 구현이 완료되었다고 표시하지 않는다.
- 담당자 결정 후 검토자·날짜·근거를 남기고 용어 상태를 갱신한다. 설계 선택의 이유는 [설계 결정](decisions.md)에 기록하고 질문은 해결 상태로 보존한다.
- API 예제·보안 계약·상품 변형 명세는 [서비스 명세](services.md), [인증 경계](p3-commerce-security.md), [상품 관계와 빌드](p2-product-family-and-builds.md)를 연결한다.
