# 이용권과 다운로드 모델

[전체 안내](../model-guide.md) · [구매 흐름](../workflows.md#purchase-flow)

License는 이용권 지급·회수의 원본이다. Download는 요청마다 License를 호출하는 대신 전달받은 사본으로 접근을 판단한다. 따라서 결제 완료, 이용권 지급, 다운로드 사본 반영은 서로 다른 시점이다.

<a id="license"></a>
## License — 주문으로 지급한 이용권 원본

저장: License 서비스의 MySQL `license`. [모델](../../../apps/license/src/main/java/com/stove/license/core/domain/License.java)

| 속성 | 뜻과 사용처 |
|---|---|
| `id` (`Long`) | 이용권 ID |
| `orderNo` (`String`) | 어느 주문으로 지급했는지. 환불 시 회수 범위 |
| `memberId`, `productId` (`Long`) | 권한을 받은 구매자·상품 |
| `licenseKey` (`String`) | 발급한 이용권 키. 다운로드 API는 이 키 대신 인증된 회원·보유 상품으로 판단 |
| `status` (`LicenseStatus`) | `ACTIVE` 보유 중, `REVOKED` 회수됨 |
| `issuedAt`, `revokedAt` (`Instant`) | 지급·회수 시각 |
| `revokeReason` (`String`) | 회수 사유 |

**만들고 쓰는 때:** `PaymentCompleted` 수신 시 주문에 등장하는 상품마다 지급하고 `LicenseIssued`를 보낸다. `(orderNo, productId)`별 한 건이므로 같은 주문에서 수량이 2이거나 같은 상품이 여러 항목에 있어도 이용권 두 개를 만들지 않는다. `PaymentCancelled`가 오면 해당 주문의 이용권을 회수하고 `LicenseRevoked`를 보낸다. 라이브러리는 회원의 ACTIVE 이용권을 조회한다.

현재 모델에는 좌석 수·선물 수령자·DLC 본편 보유 요건·묶음 구성품 지급을 표현한 별도 필드가 없다. 주문의 수량과 지급 단위가 다른 문제는 [Q5](../open-questions.md#q5)에 남아 있다. [LicenseService](../../../apps/license/src/main/java/com/stove/license/core/service/LicenseService.java)

**업무 규칙 제안:** 결제 완료를 근거로 지급하고 환불 완료를 근거로 회수한다. 같은 주문 상품을 중복 지급하지 않고 지급·회수 이력을 남긴다.

**사례:** 같은 결제 이벤트가 다시 와도 이용권은 하나다. 환불로 회수된 같은 주문의 이용권을 결제 이벤트 재처리로 되살리는 것은 허용하지 않는다. 현재 수량 2개도 상품별 이용권은 한 건이므로 다중 좌석 구매 정책으로 해석할 수 없다.

**합의 상태:** 초안. 번들·데모·수량·재구매 정책은 [Q5](../open-questions.md#q5).

**검증 근거:** [License](../../../apps/license/src/main/java/com/stove/license/core/domain/License.java), [LicenseService](../../../apps/license/src/main/java/com/stove/license/core/service/LicenseService.java), [PaymentEventListener](../../../apps/license/src/main/java/com/stove/license/api/listener/PaymentEventListener.java). [LicenseIdempotencyTest](../../../apps/license/src/integrationTest/java/com/stove/license/core/service/LicenseIdempotencyTest.java), [LicenseIssueEventTest](../../../apps/license/src/integrationTest/java/com/stove/license/core/service/LicenseIssueEventTest.java), [LicenseIssueFailureTest](../../../apps/license/src/integrationTest/java/com/stove/license/core/service/LicenseIssueFailureTest.java). 실패 분류의 설계 근거는 [decisions.md 22번](../../decisions.md#22-실패-분류는-두-번째-사례가-나올-때까지-license-안에-둔다).

<a id="entitlement"></a>
## Entitlement — 다운로드 판단용 보유권 사본

저장: Download의 MongoDB `entitlement`. [모델](../../../apps/download/src/main/java/com/stove/download/core/domain/Entitlement.java)

| 속성 | 뜻과 사용처 |
|---|---|
| `id` (`String`) | `memberId:productId`로 조합한 저장 키 |
| `memberId`, `productId` (`Long`) | 회원·상품 |
| `orderNo` (`String`) | 현재 이 사본을 부여한 출처 주문 |
| `active` (`boolean`) | 지금 다운로드 권한이 있는지 |
| `grantedAt`, `revokedAt` (`Instant`) | 사본의 부여·회수 시각 |

**만들고 쓰는 때:** `LicenseIssued`로 저장·갱신하고 `LicenseRevoked`로 비활성화한다. 회원·상품당 하나이므로 License의 주문별 지급 이력을 모두 보존하는 구조가 아니다. 회수 시 출처 주문을 비교해 재구매 이후 도착한 옛 주문의 회수가 현재 권한을 지우지 않게 한다.

고정된 문서 키는 중복 문서 생성을 막지만 모든 순서 역전을 해결하지는 않는다. 예를 들어 지연된 지급 이벤트를 무조건 최신 사실로 볼 수 있는지는 별도 문제다. [EntitlementService](../../../apps/download/src/main/java/com/stove/download/core/service/EntitlementService.java), [Q5](../open-questions.md#q5).

**업무 규칙 제안:** 지급·회수 사실을 다운로드에 반영하고, 회수된 옛 주문이 새 구매의 접근을 막지 않도록 한다. 권한과 배포 파일이 준비된 때만 서명 URL을 발급한다.

**사례:** 주문 A 환불 뒤 주문 B로 재구매했을 때 늦게 온 A의 회수 이벤트는 B의 사본을 회수하지 않는다. 보유권이 없으면 공개 매니페스트가 존재해도 티켓을 받을 수 없다. 늦은 지급 이벤트와 여러 활성 주문의 합산은 [Q5](../open-questions.md#q5).

**합의 상태:** 재검토 필요. 원본과 사본의 수량·순서 의미는 [Q5](../open-questions.md#q5). 복구 절차는 [이용권 DB 유실 runbook](../../runbooks/license-db-loss.md).

**검증 근거:** [Entitlement](../../../apps/download/src/main/java/com/stove/download/core/domain/Entitlement.java), [EntitlementService](../../../apps/download/src/main/java/com/stove/download/core/service/EntitlementService.java), [DownloadTicketService](../../../apps/download/src/main/java/com/stove/download/core/service/DownloadTicketService.java). [DownloadEntitlementTest](../../../apps/download/src/integrationTest/java/com/stove/download/core/service/DownloadEntitlementTest.java)의 `staleRevokeMustNotAffectNewEntitlement`, `rejectsUnownedProduct`, `selectsPlatformAndDeltaWithFullFallback`.

## ProductRef — 상품 코드와 구매 ID의 연결

저장: Download의 MongoDB `product_ref`. [모델](../../../apps/download/src/main/java/com/stove/download/core/domain/ProductRef.java)

| 속성 | 뜻과 사용처 |
|---|---|
| `id` (`String`) | `productCode`를 저장 키로 사용 |
| `productId` (`Long`) | Catalog 상품 ID. Entitlement 조회에 사용 |
| `name`, `status` (`String`) | 상품명·판매 상태의 사본 |
| `releaseId` (`Long`) | Catalog가 현재 가리키는 공개 릴리스 |

**만들고 쓰는 때:** `ProductChanged`로 갱신한다. `/downloads/{productCode}` 요청을 상품 ID와 정확한 릴리스 ID로 번역한다. 현재 다운로드 티켓의 권한 판단은 이 모델의 판매 상태가 아니라 보유권과 공개 릴리스 참조를 사용한다. 판매 중지가 기존 구매자의 다운로드 회수와 같은 뜻은 아니다. [ProductRefService](../../../apps/download/src/main/java/com/stove/download/core/service/ProductRefService.java)

## PatchManifest — 릴리스별 배포 파일 목록

저장: Download의 MongoDB `patch_manifest`. [모델](../../../apps/download/src/main/java/com/stove/download/core/domain/PatchManifest.java)

| 속성 | 뜻과 사용처 |
|---|---|
| `id` (`String`) | 상품 코드와 릴리스 ID를 조합한 키 |
| `productCode` (`String`), `gameId` (`Long`) | 상품 코드·프로젝트 |
| `releaseId`, `buildId`, `metadataRevision` (`Long`) | 릴리스·기본 빌드·상점 자료 ID |
| `version` (`String`) | 기본 빌드의 제품 버전 |
| `fileSize` (`long`), `checksum`, `storagePath` (`String`) | 기본 파일 크기·체크섬·저장 위치 |
| `buildVariants` (`List<BuildVariant>`) | OS·아키텍처별 전체/델타 파일 정보 |
| `releasedAt` (`Instant`) | 릴리스 이벤트의 공개 시각 |

**만들고 쓰는 때:** LIVE `ReleasePublished`를 받아 저장한다. 다운로드할 때 단순히 가장 큰 게임 버전 문자열을 고르지 않고 ProductRef가 지정한 릴리스의 매니페스트를 사용한다. 롤백도 새 릴리스의 파일 목록으로 표현할 수 있다. [ManifestService](../../../apps/download/src/main/java/com/stove/download/core/service/ManifestService.java)

## DownloadTicket·SignedUrl — 요청마다 발급하는 다운로드 결과

[DownloadTicket](../../../apps/download/src/main/java/com/stove/download/core/domain/DownloadTicket.java)은 DB에 보유권으로 저장하는 객체가 아니라 권한 검사 후 반환하는 값이다.

| 속성 | 뜻과 사용처 |
|---|---|
| `productCode`, `version` (`String`), `releaseId`, `buildId` (`Long`) | 이번에 받을 상품·버전·릴리스·실제 선택된 빌드 |
| `fileSize` (`long`), `checksum` (`String`) | 클라이언트가 확인할 파일 크기·지문 |
| `downloadUrl` (`String`), `expiresAt` (`Instant`) | 만료 시각이 있는 서명 URL |
| `platform`, `architecture`, `deltaFromVersion` (`String`) | 선택한 환경·델타 출발 버전. 전체 파일이면 델타 출발 버전은 null |

[SignedUrl](../../../apps/download/src/main/java/com/stove/download/core/domain/SignedUrl.java)은 서명 어댑터가 반환하는 `url`, `expiresAt` 두 값이다. 이 값과 파일 정보를 합쳐 티켓을 만든다. 서명 URL은 접근 기한을 가진 주소이지 영구적인 이용권 ID가 아니다.

**사용 순서:** [DownloadTicketService](../../../apps/download/src/main/java/com/stove/download/core/service/DownloadTicketService.java)는 ProductRef 조회→Entitlement 보유 확인→해당 릴리스의 PatchManifest 확인→파일 선택→URL 서명 순서로 처리한다. 플랫폼을 지정하지 않으면 기본 파일을 선택한다. 플랫폼·아키텍처·설치 버전을 지정하면 일치하는 델타를 우선 선택하고 없으면 같은 환경의 전체 파일로 돌아간다. 델타 파일 생성·설치 적용 자체를 이 서비스가 수행하지는 않는다.

**예:** 회원 25가 상품 42를 보유하고 `STAR-FOREST`를 요청한다. ProductRef가 상품 42·릴리스 601을 가리키면, 릴리스 601의 파일만 선택한다. 빌드가 저장소에 존재해도 보유권이나 해당 공개 참조가 없으면 이 흐름으로 파일을 받을 수 없다.

기존 검증: [DownloadEntitlementTest](../../../apps/download/src/integrationTest/java/com/stove/download/core/service/DownloadEntitlementTest.java), [이용·정산 여정](../../../e2e/src/test/java/com/stove/e2e/TrackCFulfillmentFlowTest.java).

**API에서 선택하기:** `GET /api/v1/downloads/{productCode}/manifests`로 공개 변형을 확인한다. 예를 들어 `GET /api/v1/downloads/GAME-001/ticket?platform=WINDOWS&architecture=X86_64&fromVersion=1.0.0`은 해당 환경·설치 버전의 델타가 있으면 선택하고, 없으면 같은 환경의 전체 빌드를 선택한다. 인증·보유권 검사는 그대로 적용된다.
