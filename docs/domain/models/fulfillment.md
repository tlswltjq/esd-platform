# 이용권과 다운로드 모델

[전체 안내](../model-guide.md) · [구매 흐름](../workflows.md#purchase-flow)

License는 완료 주문 하나의 출처와 구매자·상품별 영구 이용 권한을 한 엔티티에 보존한다.
Order는 PENDING·PAID 주문이 있는 동일 상품의 추가 구매를 차단한다. 선물·추가 수량은 후속 범위다.
Download는 보유권과 현재 공개 빌드를 조회하고 전체 ZIP의 만료 URL을 발급한다.
[Download MVP 범위·검증](../../download-mvp-plan.md) · [실행 안내](../../download-guide.md)
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
상품 후속 버전에도 같은 영구 보유권을 사용한다. 환불을 위한 ACTIVE/SUSPENDED와 지급·정지·복구 근거가 추가됐다. 신규 Download는 ACTIVE만 허용한다. [현재 환불 계약](../../refund-session-handoff.md). 기간 만료·좌석 수·빌드 제한은 범위 밖이다.
조회 주체는 JWT sub이며 Catalog 조회 없이 내 목록·상품 코드별 상세를 반환한다.

[PaidOrder](../../../apps/license/src/main/java/com/stove/license/core/domain/PaidOrder.java)는 OrderPaid의 13개 값을 담고 생성 시 유효성을 보장하는 내부 입력이다.
License.paidOrder()가 저장된 출처를 재구성하고 verify(...)가 전체 입력을 대조한다.
같은 주문의 재전달은 원래 eventId·발생 시각·본문 모두 일치해야 한다. 다른 주문에서 출처 식별자를 재사용할 수 없다.

[GrantLicense](../../../apps/license/src/main/java/com/stove/license/core/service/GrantLicense.java)는 출처·상품 충돌과 Inbox를 비교하고, 출처를 포함한 권한·Inbox·LicenseGranted Outbox를 같은 READ_COMMITTED 트랜잭션에서 저장한다.
동시 최초 지급의 유일 제약 경합은 전체 롤백하고 Kafka 재전달의 새 트랜잭션에서 다시 판단한다.
같은 원본이면 한 권한으로 수렴한다. 다른 주문의 같은 상품이면 충돌이다.
외부 이벤트 변환은 [LicenseOrderMapper](../../../apps/license/src/main/java/com/stove/license/api/listener/LicenseOrderMapper.java)가 맡는다.
[통합 검증](../../../apps/license/src/integrationTest/java/com/stove/license/LicenseIntegrationTest.java)과
[실제 아홉 앱 검증](../../../e2e/src/test/java/com/stove/e2e/LicensePurchaseFlowTest.java)을 사용한다.

V5에서 LicensePurchase·저장소를 제거했다. 기존 license_purchase 테이블은 전환 전 자료를 보존하며 런타임에서 읽거나 쓰지 않는다.

## DownloadRequest·OwnedProduct — 요청자와 보유 상품

Download에는 저장 엔티티·업무 DB·보유권 사본이 없다. 모든 모델은 한 번의 발급 요청에서 사용하는 값이다.

| 모델 | 값·사용 시점 |
|---|---|
| [DownloadRequest](../../../apps/download/src/main/java/com/stove/download/core/domain/DownloadRequest.java) | 검증된 JWT sub의 buyerSubject와 요청 productCode. 생성 시 주체·코드 형식을 검사 |
| [OwnedProduct](../../../apps/download/src/main/java/com/stove/download/core/domain/OwnedProduct.java) | License가 현재 사용자에게 반환한 productId·productCode. 생성 시 양수 ID·코드 검사 |

LicenseHttpClient가 원래 사용자 access token을 고정된 License 주소에 전달한다.
라이브러리 상세의 404는 Download에서 미보유 403이 되고, 연결 장애·잘못된 응답은 503이다.
다른 사용자의 보유권 조회나 Order PAID를 이용한 임시 권한 생성은 없다.

## PublishedBuild — 현재 공개된 전체 빌드

[모델](../../../apps/download/src/main/java/com/stove/download/core/domain/PublishedBuild.java)은 Catalog 내부 조회 응답을 검증한 뒤 생성한다.

| 값 | 뜻·사용 |
|---|---|
| productId·productCode | License의 보유 상품과 두 값 모두 일치해야 발급 |
| releaseRequestId·contentVersion | Catalog 조회 시점의 현재 COMPLETED 공개본 식별·상세정보 버전 |
| buildId·version | 공개 스냅샷에 고정한 전체 ZIP 빌드와 버전 표시 문자열 |
| fileSize·checksum | 양수 바이트 크기·SHA-256. HEAD 크기 대조와 클라이언트 무결성 확인 |
| storagePath | Catalog에 보존한 s3://버킷/객체키. 설정된 버킷·비어 있지 않은 키만 사용 |

버전 문자열이나 가장 큰 ID로 최신을 정하지 않는다. Catalog의 currentReleaseRequestId를 기준으로 한 불변 스냅샷이다.
조회 직후 새 공개본이 확정되어도 이미 선택한 빌드로 발급할 수 있다. 다음 조회는 새 현재 공개본을 따른다.
내부 조회는 전용 서비스 키를 요구하고 Gateway와 공개 OpenAPI에는 노출하지 않는다.

## DownloadTicket·SignedUrl — 발급 결과

[ReadBuildDownload](../../../apps/download/src/main/java/com/stove/download/core/service/ReadBuildDownload.java)는
License 보유 조회 → Catalog 공개 빌드 조회 → 상품 연결 대조 → 저장소 확인·URL 발급을 조율한다.
[BuildStorage](../../../apps/download/src/main/java/com/stove/download/core/port/BuildStorage.java)의 S3 어댑터가
읽기 전용 계정으로 객체 HEAD·크기 확인 후 presigned GET을 생성한다. 파일 누락·크기 불일치는 409다.

[SignedUrl](../../../apps/download/src/main/java/com/stove/download/core/domain/SignedUrl.java)은 url·expiresAt 두 값이다.
[DownloadTicket](../../../apps/download/src/main/java/com/stove/download/core/domain/DownloadTicket.java)은
productCode·version·fileSize·checksum·downloadUrl·expiresAt 여섯 값으로 사용자에게 반환한다.
기본 5분 URL이며 보유권을 소모하거나 발급 기록을 저장하지 않는다. 파일은 저장소에서 클라이언트로 직접 전송한다.

URL의 만료는 새 GET 요청에 적용되고, License 영구 보유권의 만료가 아니다.
URL을 가진 사람의 공유·반복 GET을 차단하거나 이미 시작한 전송을 종료하지 않는다.
License·Catalog 장애 중 새 발급은 503이지만 이미 발급한 URL은 저장소가 동작하면 만료 전 사용할 수 있다.

검증: [실제 JWT·MinIO 통합 검사](../../../apps/download/src/integrationTest/java/com/stove/download/DownloadIntegrationTest.java),
[실제 열 앱 구매·다운로드 흐름](../../../e2e/src/test/java/com/stove/e2e/DownloadPurchaseFlowTest.java).

<a id="entitlement"></a>
<a id="product-ref"></a>
<a id="patch-manifest"></a>
## 제거한 사본·패치 모델

구 Entitlement·ProductRef·PatchManifest와 MongoDB 저장소·이벤트 리스너·패치/플랫폼 선택은 MVP에서 제거했다.
구 MongoDB 자료와 소비 그룹 오프셋은 자동 이관·물리 삭제하지 않는다.
날짜가 있는 과거 사본·환불 실험은 당시 구현의 기록이며 현재 Download의 업무 계약이 아니다.
