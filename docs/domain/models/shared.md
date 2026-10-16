# 공통 속성·감사 기록·이벤트 값

[전체 안내](../model-guide.md) · [이벤트 업무 흐름](../workflows.md#이벤트를-업무-사실로-읽기)

공통 모델은 게임·주문 자체를 나타내기보다 기록 시각과 서비스 간 전달을 지원한다. 업무 데이터의 원본, 전송할 메시지, 이미 처리했다는 기록을 구별하면 비동기 흐름을 읽기 쉽다.

## BaseTimeEntity — 생성·수정 시각

[코드](../../../common/jpa/src/main/java/com/stove/common/jpa/BaseTimeEntity.java). 별도 테이블이 아니라 상속한 엔티티 테이블에 다음 속성이 포함된다.

| 속성 | 뜻과 사용처 |
|---|---|
| `createdAt` (`Instant`) | 행을 생성한 시각 |
| `updatedAt` (`Instant`) | 행을 마지막으로 변경한 시각 |

이 시각은 결제 발생일·파일 공개일·외부 인증일과 같다는 보장이 없다. 그런 업무 시각은 `paidAt`, `publishedAt`, `issuedAt` 등 별도 필드로 표현한다.

| 서비스 | 현재 상속하는 모델 |
|---|---|
| auth | `UserAccount` |
| studio | `Workspace`, `GameProject`, `StorePageRevision`, `PricingRevision`, `RatingRevision`, `GameBuild`, `UploadSession`, `Submission`, `Release`, `ProjectCredential`, `CiTrustPolicy`, `InternalTesterGrant`, `BuildWebhookSubscription`, `BuildWebhookDelivery` |
| review | `SubmissionSnapshot`, `ReviewCase`, `ReviewDecisionHistory`, `ReviewRequest` |
| catalog | `Product` |
| order / payment / license | 각각 `Order`, `Payment`, `License` |
| settlement | `SettlementRecord`, `SellerSettlement` |

모든 저장 모델이 이 클래스를 상속하지는 않는다. 예를 들어 OrderItem·Promotion·SubmissionGate에 생성 시각이 자동으로 있다고 가정하면 안 된다.

<a id="audit-log"></a>

## AuditLog — 누가 무엇을 했는지 남기는 기록

아래는 재작성 전 감사 모델 설명이다. 현재 Review·Catalog의 해당 업무 모델은 제거됐으며 구 DB 자료는 보존한다. 당시 Studio·Review·Catalog는 각자 MySQL DB에 audit_log를 뒀다. 같은 필드 구조여도 세 서비스가 한 테이블을 공유하지 않는다.

| 속성 | 뜻과 사용처 |
|---|---|
| `id` (`Long`) | 감사 행 ID |
| `actor`, `action` (`String`) | 행동한 사용자/시스템과 행동명 |
| `targetType`, `targetId` (`String`) | 대상 종류·식별자 |
| `details` (`String`) | 당시 추가 정보 |
| `occurredAt` (`Instant`) | 기록 시각 |

**만들고 쓰는 때:** 공개·예약·심사·판매 상태 변경 등의 서비스가 업무 처리와 함께 기록한다. 현재 상태를 조회하는 원본 엔티티나 다른 서비스에 보내는 이벤트를 대신하지 않는다. 각 서비스의 [Studio 심사·등록 기록](studio.md), Review AuditLog(제거된 구 구현), Catalog AuditLog(제거된 구 구현)을 참고한다. 역할 부여는 RoleAuditLog, 결제 행동은 PaymentAuditLog, 사건별 전이는 ReviewDecisionHistory로 별도 설명했다.

## OutboxEvent — 발송할 이벤트의 보관함

저장: 사용하는 서비스의 MySQL `outbox_event`. [모델](../../../common/messaging/src/main/java/com/stove/common/messaging/outbox/OutboxEvent.java)

| 속성 | 뜻과 사용처 |
|---|---|
| `id` (`Long`), `eventId` (`String`) | 보관함 행 ID·서비스 사이에서 유지하는 이벤트 고유 ID |
| `aggregateType`, `aggregateId` (`String`) | 어떤 업무 대상에서 발생했는지 |
| `eventType` (`String`) | `PaymentCompleted` 같은 사건 종류 |
| `topic`, `partitionKey` (`String`) | 전달할 Kafka 통로와 같은 대상의 순서를 묶는 키 |
| `payload` (`String`) | 전송할 JSON 내용 |
| `traceParent` (`String`) | 원 요청의 추적 정보를 발송 작업으로 이어주는 값 |
| `status` (`OutboxStatus`) | `PENDING` 대기, `SENT` Kafka 수신 확인, `DEAD` 발행 재시도 소진 |
| `retryCount` (`int`), `nextAttemptAt` (`Instant`) | 실패 횟수·다음 발행 예약 |
| `lastError` (`String`) | 마지막 발행 실패 설명 |
| `createdAt`, `sentAt` (`Instant`) | 적재·발행 확인 시각 |

**만들고 쓰는 때:** 업무 모델 변경과 같은 DB 트랜잭션에서 발송할 내용을 저장한다. 별도 발송 작업이 Kafka에 전송한다. DB 변경은 성공했는데 메시지를 보내기 전에 프로세스가 중단돼도 보관함에 남은 것을 다시 보낼 수 있다. `SENT`는 Kafka가 받았다는 뜻이며 모든 수신 서비스의 처리가 완료됐다는 뜻은 아니다.

## ProcessedEvent — 수신 이벤트 처리 기록

저장: 사용하는 서비스의 MySQL `processed_event`. Inbox라고도 부른다. [모델](../../../common/messaging/src/main/java/com/stove/common/messaging/inbox/ProcessedEvent.java)

| 속성 | 뜻과 사용처 |
|---|---|
| `id` (`Long`) | 수신 처리 기록 ID |
| `eventId`, `consumerGroup` (`String`) | 어느 처리 주체가 어느 이벤트를 처리했는지. 두 값의 조합이 유일 |
| `eventType` (`String`) | 처리한 사건 종류 |
| `processedAt` (`Instant`) | 처리 기록 시각 |

**만들고 쓰는 때:** 주문·결제·이용권·정산 등의 수신 처리가 업무 변경과 같은 트랜잭션에 남긴다. 동일 이벤트를 다시 받으면 중복 반영을 막는다. 모든 서비스가 사용하는 것은 아니다. Store·Download는 고정 문서 키 갱신 등의 방식으로 사본을 유지한다. “중복 방어”와 “서로 다른 이벤트의 순서 역전 방어”는 다른 문제다.

## EventEnvelope와 이벤트 payload — 전달할 사실

[EventEnvelope](../../../common/kafka/src/main/java/com/stove/common/kafka/EventEnvelope.java)는 `eventId`, `eventType`, `key`, `payload`를 묶은 수신 값이다. 메시지 종류를 판별하고 JSON 본문을 해당 이벤트 record로 읽는다. 개별 업무 이벤트는 [payload 소스](../../../common/kafka/src/main/java/com/stove/common/kafka/contract/payload)에 있다. 모든 엔티티 필드를 자동으로 직렬화해 전달하는 구조가 아니다.

재작성 완료 모듈의 수신 경계에서는 `api.listener`의 `*Mapper.from(...)`가 봉투·본문·키를 검증하고 내부 입력으로 바꾼다. 업무 서비스와 도메인 모델은 봉투나 외부 이벤트 전체를 입력받지 않는다. 이벤트 ID·종류·발생 시각은 필요한 내부 값으로 전달하며 Inbox와 업무 변경은 서비스의 같은 트랜잭션에 둔다. [작성 규칙](../../use-case-conventions.md#진입점과-설정의-역할)을 따른다.

| 사건 | 대표 연결·전달 값 | 받는 쪽의 용도 |
|---|---|---|
| `UserRegistered` | `subject`, `email` | Studio 개인 작업 공간 생성 |
| `SubmissionCreated` | 제출 ID, 자료 ID, 기본 빌드·변형, 제목·가격·등급 설문 | Review 심사 사본·사건 생성 |
| `SubmissionReviewApproved` | 제출·유형·승인 자료 ID와 등급 결과 | Studio 관문에 승인 반영 |
| `ReviewChangesRequested`, `ReviewAppealed` | 제출·심사 유형·사유 등 | Studio 수정 요청·재검토 반영 |
| `ReleasePublished` | 릴리스·상품 코드·자료·가격·파일·공개 소개 | Catalog 상품, Download 배포 파일 반영 |
| `ProductChanged` | 상품 ID·코드·릴리스·판매 상태·정가·행사 일정·변경 순번 | Store 검색, Download 상품 참조 갱신 |
| `OrderPlaced` | 주문번호·buyerSubject·상품·정가·할인액·청구액 | 후속 Payment의 결제 근거 |
| `OrderPaymentCompleted` / `OrderPaymentFailed` | 원래 주문 이벤트·주문번호·구매자·결제·금액·통화 | Order 최종 상태 반영 |
| `OrderCreated` (구 계약) | 주문번호·회원·총액·주문 항목 | 미완료 Payment의 기존 입력 |
| `PaymentCompleted` | 주문번호·회원·결제 정보·주문 항목 | 미완료 License·Settlement의 기존 입력. 새 Order에서는 사용하지 않음 |
| `PaymentFailed` | 주문번호·거절 사유 | 구 주문 결과 계약. 새 Order에서는 사용하지 않음 |
| `PaymentCancelled` | 주문번호·환불 사유 등 | 미완료 이용권 회수·환불 원장의 구 계약 |
| `LicenseIssued`, `LicenseRevoked` | 주문번호·회원·상품 ID 목록 | Download 보유권 사본 갱신 |
| `LicenseIssueFailed` | 주문번호·회원·실패 사유 | 보상 환불 검토·처리 |

이 표는 대표 흐름이며 모든 이벤트 목록은 [EventType](../../../common/kafka/src/main/java/com/stove/common/kafka/contract/EventType.java)에 있다. 이벤트 타입이 정의됐다는 사실만으로 모든 관련 서비스가 소비한다고 가정하지 않는다. 예를 들어 현재 `OrderCanceled`는 Payment의 주문 리스너에서 처리하지 않는다.

<a id="build-variant"></a>

## BuildVariant — 공개·제출 묶음의 파일 한 개

[모델](../../../common/kafka/src/main/java/com/stove/common/kafka/contract/payload/BuildVariant.java). `buildId`, `platform`, `architecture`, `version`, `fileSize`, `checksum`, `storagePath`, `deltaFromVersion`을 담는다. GameBuild에서 필요한 값만 복사해 심사·공개 이벤트와 PatchManifest에 넣는다. `deltaFromVersion=null`이면 전체 파일이고, 값이 있으면 그 버전에서 적용할 델타다. 빌드 검증 상태·CI 비밀정보·업로드 세션 전체를 전달하지 않는다.

## StorefrontSnapshot — 공개된 소개 자료

[모델](../../../common/kafka/src/main/java/com/stove/common/kafka/contract/payload/StorefrontSnapshot.java). ReleaseService가 승인된 상점 자료에서 만드는 값으로 Catalog·Store에 전달한다.

| 속성 | 담는 내용 |
|---|---|
| `title`, `shortDescription`, `detailedDescription`, `localizations` | 기본·언어별 제목과 소개. `LocalizedContent`도 제목·짧은 소개·상세 소개 |
| `genres`, `tags`, `developer`, `publisher` | 장르·태그·표시 개발사·배급사 |
| `screenshots`, `trailers`, `iconUrl`, `coverUrl` | 고객에게 보여줄 이미지·영상 |
| `supportedLanguages`, `platform`, `minimumRequirements`, `recommendedRequirements`, `features` | 실행 환경·지원 정보 |
| `supportUrl`, `privacyPolicyUrl`, `eulaUrl`, `salesCountries` | 지원·정책·계약 URL과 판매 국가 |

StorePageContent와 닮았지만 `prices`는 포함하지 않는다. 공개 가격은 선택한 PricingRevision에서 별도로 전달한다.

## PromotionWindow·OrderLine — 가격 일정과 구매 조건

[PromotionWindow](../../../common/kafka/src/main/java/com/stove/common/kafka/contract/payload/PromotionWindow.java)는 `id`, `discountPerUnit`, `bearer`, `startsAt`, `endsAt`을 담는 행사 사본이다. Store가 현재 시각으로 할인 적용 여부를 계산한다. 원본 Promotion의 `sellerId`·`stoppedAt`을 그대로 모두 복제한 객체는 아니다.

[OrderLine](../../../common/kafka/src/main/java/com/stove/common/kafka/contract/payload/OrderLine.java)은 주문 당시 상품·단가·수량·할인·정산 조건이다. 전체 필드 의미는 [OrderItem과의 대응 표](order-payment.md#order-item)에 있다. 행사 일정은 앞으로/지금 적용할 조건이고, OrderLine은 구매 순간 이미 확정된 조건이다.
