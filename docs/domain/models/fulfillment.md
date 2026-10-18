# 이용권과 다운로드 모델

[전체 안내](../model-guide.md) · [구매 흐름](../workflows.md#purchase-flow)

License는 완료 주문 하나의 출처와 구매자·상품별 영구 이용 권한을 한 엔티티에 보존한다.
Order는 PENDING·PAID 주문이 있는 동일 상품의 추가 구매를 차단한다. 선물·추가 수량은 후속 범위다.
Download는 다음 재작성 대상이다. 아래 Download 모델은 구 구현의 참고이며 새 License의 연계 요구가 아니다.
[License 통합 범위](../../license-mvp-plan.md#통합-및-중복-구매-제한-수정-계획) · [조회·실행 안내](../../license-guide.md)

<a id="license"></a>
## License — 구매 출처를 보존하는 상품 이용 권한

저장: License의 MySQL `product_license`. [모델](../../../apps/license/src/main/java/com/stove/license/core/domain/License.java)

| 속성 | 뜻과 사용처 |
|---|---|
| `id` (`Long`) | 내부 저장 키 |
| `licenseId` (`String`) | 공개 UUID. CD키·다운로드 토큰이 아님 |
| `buyerSubject` (`String`) | Auth JWT sub에서 유래한 구매자 |
| `productId`, `productCode`, `title` | 주문 당시 고정 상품 식별자와 제목 |
| `eventId`, `occurredAt` | OrderPaid의 식별자와 Order의 최초 PAID 반영 시각 |
| `orderNo`, `requestEventId` | 출처 주문 UUID와 원래 OrderPlaced 이벤트 ID |
| `paymentResultEventId`, `paymentId` | 주문에 반영한 결제 결과 이벤트와 고정 결제 ID |
| `contentVersion`, `amount`, `currency` | 주문 당시 버전·고객 청구액·통화 |
| `acquiredAt`, `receivedAt` (`Instant`) | 최초 권한 부여·출처 접수 시각. 신규 지급은 같은 값, 전환 자료는 원래 두 시각 보존 |

`OrderPaid`를 처음 처리할 때 출처와 권한을 한 행으로 생성한다.
구매자·상품 ID와 구매자·상품 코드, 출처 주문·이벤트·요청 이벤트·결과 이벤트·결제 ID는 각각 유일하다.
다른 주문의 같은 상품을 기존 권한에 추가하거나 출처를 교체하지 않는다. 예외 입력은 충돌로 남긴다.
상품 후속 버전에도 같은 영구 보유권을 사용한다. 만료·회수 상태·좌석 수·빌드 제한은 이번 범위에 없다.
조회 주체는 JWT sub이며 Catalog 조회 없이 내 목록·상품 코드별 상세를 반환한다.

[PaidOrder](../../../apps/license/src/main/java/com/stove/license/core/domain/PaidOrder.java)는 OrderPaid의 13개 값을 담고 생성 시 유효성을 보장하는 내부 입력이다.
License.paidOrder()가 저장된 출처를 재구성하고 verify(...)가 전체 입력을 대조한다.
같은 주문의 재전달은 원래 eventId·발생 시각·본문 모두 일치해야 한다. 다른 주문에서 출처 식별자를 재사용할 수 없다.

[GrantLicense](../../../apps/license/src/main/java/com/stove/license/core/service/GrantLicense.java)는 출처·상품 충돌과 Inbox를 비교하고, 출처를 포함한 권한·Inbox를 같은 READ_COMMITTED 트랜잭션에서 저장한다.
동시 최초 지급의 유일 제약 경합은 전체 롤백하고 Kafka 재전달의 새 트랜잭션에서 다시 판단한다.
같은 원본이면 한 권한으로 수렴한다. 다른 주문의 같은 상품이면 충돌이다.
외부 이벤트 변환은 [LicenseOrderMapper](../../../apps/license/src/main/java/com/stove/license/api/listener/LicenseOrderMapper.java)가 맡는다.
[통합 검증](../../../apps/license/src/integrationTest/java/com/stove/license/LicenseIntegrationTest.java)과
[실제 아홉 앱 검증](../../../e2e/src/test/java/com/stove/e2e/LicensePurchaseFlowTest.java)을 사용한다.

V5에서 LicensePurchase·저장소를 제거했다. 기존 license_purchase 테이블은 전환 전 자료를 보존하며 런타임에서 읽거나 쓰지 않는다.

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
