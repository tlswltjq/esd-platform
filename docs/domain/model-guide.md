# 코드로 이해하는 도메인과 모델

현재 완료된 기준 모듈은 Auth·Gateway·Studio·Review·Catalog·Store·Order·Payment·License·Download다. 신규 구현과 검증은 이 열 모듈의 계약을 따르며, 나머지 모듈 설명은 기존 구현의 참고 기록이다.

이 프로젝트는 **창작자가 게임을 준비하고 심사를 거쳐 판매하면, 구매자에게 이용 권한을 주고 판매자의 몫을 계산하는 시스템**이다. 먼저 이 흐름을 이해한 뒤 각 단계가 보관하는 정보를 읽으면 된다.

이 문서는 2026-10-10, 로컬 `docs/domain-language`의 코드 `076efb1`을 기준으로 작성했다. [용어집](../domain-glossary.md)은 용어를 찾고 혼동을 푸는 색인이다. 이 안내와 모델 사전은 **어떤 객체가 어떤 속성을 가지고, 누가 언제 만들고 바꾸며, 다음 단계에서 무엇에 쓰이는지** 설명한다. 현재 구현에 대한 설명이며 업무 정책에 합의했다는 뜻은 아니다. 기존 상세 정의의 규칙 제안·사례·합의 상태도 각 모델에 모았다. 실행·설계·검증 문서는 [전체 문서 목록](../README.md)에서 찾는다.

## 처음 읽는 순서

1. 이 페이지의 업무 지도와 게임 한 개의 여정을 읽는다.
2. 관심 있는 단계의 모델 사전으로 이동한다. 처음에는 각 모델의 첫 문장과 “만들고 쓰는 때”만 읽어도 된다.
3. 낯선 필드가 나오면 속성 표를, 실제 요청 순서가 궁금하면 [대표 업무 흐름](workflows.md)을 읽는다.
4. 코드와 기대하는 동작이 다르면 [미결 질문](open-questions.md)을 확인한다. 상태 이름이나 주석만으로 지원 여부를 판단하지 않는다.

## 도메인·모델·속성을 읽는 법

| 말 | 이 프로젝트에서의 뜻 | 예 |
|---|---|---|
| 도메인 | 해결하려는 업무 영역 | 결제는 돈의 승인·환불, 정산은 판매자 몫 계산 |
| 서비스 | 독립 실행되는 프로그램. 도메인을 구현하는 배치 단위 | `apps/payment`, `apps/settlement` |
| 모델 | 업무 대상을 코드의 자료와 행동으로 표현한 것 | `Payment`는 금액·상태를 보관하고 승인 가능 여부를 검사 |
| 속성 / 필드 | 모델이 기억하는 정보 | `Payment.amount`는 결제해야 할 금액 |
| 엔티티 | ID로 구별하고 생애 동안 상태를 추적하는 객체 | 금액이 같아도 주문번호가 다르면 다른 주문 |
| 값 객체·record | 입력·결과·값의 묶음. 자체 저장 행이 없는 경우가 많음 | `OrderPrice`는 정가·할인액·청구액, `DownloadTicket`은 다운로드 응답 |
| 애그리거트 | 함께 규칙을 지키며 변경하는 객체 묶음 | `Order`가 상품 스냅샷·청구액·결제 결과를 함께 관리 |
| 리비전 / revision | 수정 전 자료를 보존하기 위한 판본 | 상점 설명 1차와 2차를 각각 저장 |
| 스냅샷 / snapshot | 특정 순간의 내용을 고정한 기록 | 주문 당시 가격. 상품 가격이 바뀌어도 과거 주문은 유지 |
| 읽기 모델·투영 / projection | 원본의 변경을 받아 조회 목적에 맞게 만든 사본 | 검색용 `StoreProduct` |
| 이벤트 | 다른 서비스에 전달하는 “이미 일어난 사실” | `OrderPaymentCompleted`: 결제 승인이 확정됨 |
| 멱등성 | 같은 요청·이벤트가 반복돼도 결과가 중복되지 않는 성질 | 같은 결제 이벤트로 이용권을 두 번 만들지 않음 |
| 트랜잭션 | 한 DB에서 함께 성공하거나 함께 취소할 변경 범위 | 주문 저장과 발송할 이벤트 저장을 함께 처리 |
| 비동기 | 요청 시점과 다른 서비스의 반영 시점이 분리됨 | 결제 완료 직후에는 License 보유권이 아직 지급되지 않았을 수 있음 |

모델 표의 `Long`/`long`은 정수, `String`은 문자열, `boolean`은 참·거짓, `Instant`는 시각, `BigDecimal`은 정확한 소수, `List`/`Set`은 목록/중복 없는 집합이다. 금액은 최소 화폐 단위의 정수로 다루며 현재 구매 흐름은 KRW를 전제로 한다. `Long`처럼 대문자로 시작하는 참조형은 코드상 `null`을 담을 수 있지만, 실제 필수 여부는 생성 규칙과 DB 제약도 확인해야 한다.

`id`는 각 모델을 구별하는 저장 키다. `entityVersion`은 동시에 수정한 요청의 충돌을 감지하는 번호이며 게임 버전이 아니다. `BaseTimeEntity`를 상속하는 모델에는 `createdAt`·`updatedAt`이 추가된다. 모델별 표는 직접 선언한 속성을 모두 다루고, 상속 여부는 [공통 모델](models/shared.md)에 모았다.

## 전체 업무 지도

코드에는 업무·인증 서비스 10개와 Gateway가 있다. 아래는 이해를 위한 책임 구분이며 바운디드 컨텍스트 확정은 [Q1](open-questions.md#q1)에 남아 있다.

| 영역 / 코드 | 답하는 질문 | 저장 모델 | 사전 |
|---|---|---|---|
| 계정·역할 / `auth` | 누가 요청했고 어떤 역할인가? | `UserAccount`, `RoleAuditLog` | [계정](models/identity.md) |
| 창작·출시 / `studio` | 출시 자료·파일과 심사·출시 요청을 어떻게 관리하는가? | `Project`, `Build`, `ProjectReviewRequest`, `ProjectReleaseRequest` | [창작·출시](models/studio.md) |
| 창작 자동화·운영 / `studio` | 어떤 CI·테스터가 접근하고 어디에 결과를 알리는가? | `ProjectCredential`, `CiTrustPolicy`, `InternalTesterGrant`, `BuildWebhookSubscription`, `BuildWebhookDelivery`, `AuditLog` | [창작 운영](models/studio-operations.md) |
| 심사 / `review` | 어떤 요청의 불변 자료·빌드에 대해 누가 판정했는가? | `ProjectReview` | [심사](models/review.md) |
| 공개 상품 / `catalog` | 어떤 승인 스냅샷을 현재 공개하는가? | `CatalogProduct`, `CatalogRelease` | [상품·진열](models/catalog-store.md) |
| 검색·조회 / `store` | 현재 공개된 게임을 어떻게 찾고 확인하는가? | `StoreProduct` | [상품·진열](models/catalog-store.md) |
| 주문 / `order` | 누가 무엇을 얼마에 사기로 했는가? | `Order`, `OrderPrice`, `PaymentResult` | [주문·결제](models/order-payment.md) |
| 결제 / `payment` | 확정된 주문의 결제 결과가 무엇인가? | `Payment`, `PaymentOrderRequest`, `PgChargeRequest`, `PgResult` | [주문·결제](models/order-payment.md) |
| 이용권 / `license` | 어떤 상품을 보유하고 어떤 주문이 근거인가? | `License`, `PaidOrder` | [이용·다운로드](models/fulfillment.md) |
| 파일 배포 / `download` | 본인 보유 상품의 현재 공개 빌드를 어떻게 받는가? | `DownloadRequest`, `OwnedProduct`, `PublishedBuild`, `DownloadTicket` | [이용·다운로드](models/fulfillment.md) |
| 정산 / `settlement` | 판매자에게 얼마를 배분해야 하는가? | `SettlementSale`, `SellerMonthlySettlement`, `PgReceipt` | [정산](models/settlement.md) |
| 요청 입구 / `gateway` | 요청을 어느 서비스로 보낼 것인가? | 자체 업무 엔티티 없음 | [서비스 명세](../services.md#gateway) |
| 전달·기록 / `common` | 변경 사실을 잃거나 중복 처리하지 않았는가? | `OutboxEvent`, `ProcessedEvent` 및 공유 이벤트 값 | [공통 모델](models/shared.md) |

`catalog`의 MySQL 상품은 공개본의 원본, `store`의 MySQL 상품은 검색·조회 사본이다. `license`의 MySQL은 보유권과 구매 근거의 원본이다. `download`는 License·Catalog를 조회하는 무상태 서비스이며 별도 DB·MongoDB·Kafka를 사용하지 않는다. 서비스 사이의 ID는 연결 참조이며, 여러 DB를 하나의 외래 키로 묶어 관리하는 구조는 아니다.

## 게임 한 개를 처음부터 따라가기

| 단계 | 저장·전달되는 내용 |
|---|---|
| CREATOR 가입·Project 생성 | Auth subject가 Project.ownerSubject가 되며 기존 sellerId 연결을 유지 |
| 상점 내용·파일 준비 | Project 작업본에 제목·썸네일·소개·영상·내용·가격과 대표 전체 ZIP Build 지정 |
| 단일 심사 | ProjectReviewRequest가 자료 스냅샷을 첨부한 요청과 외부 결과를 기록 |
| 출시 요청 | 현재 상세정보 버전의 외부 심사 결과로 ProjectReleaseRequest를 발행 |
| 공개 반영 | 외부 처리 결과를 ProjectReleaseRequest에 기록. 실제 출시·공개는 외부 업무 |
| 출시 후 수정 | 상세정보를 변경하고 새 버전의 심사·출시 요청을 발행. 공개본 관리 책임은 외부 업무 |
| 주문·결제 경계 | Catalog 공개 상세로 주문 스냅샷 고정, 고정 결제 키로 모의 PG 호출·미확정 재개·결과 반영 |
| 권한 부여 | OrderPaid로 출처를 포함한 상품별 License·Inbox 저장. 내 라이브러리 조회 |
| 다운로드 | License 보유권·Catalog 현재 공개 빌드 확인 → 저장소 HEAD·서명 URL → 전체 ZIP 다운로드 |
| 정산 | OrderPaid 매출·플랫폼 부담 PG 비용·판매자 월 마감·공급자 지급 명세 대사. 실제 송금·환불은 후속 |

Project는 편집 중인 내용이고 Product는 현재 등록된 상품이다.
Build는 요청에 첨부할 파일이며 ProjectReleaseRequest는 외부로 발행한 출시 요청이다.
심사 시점의 스냅샷 가격·현재 상품 가격·주문 당시 가격을 구별한다.
[현재 Studio 모델](models/studio.md)에서 필드와 상태 전이를 확인한다.

## 코드에서 답을 찾는 순서

| 궁금한 것 | 찾아볼 코드 | 읽는 요령 |
|---|---|---|
| 어떤 속성이 있나? | `core/domain/모델.java` | 필드와 생성 메서드, `approve()` 같은 상태 변경 메서드 |
| 언제 생성·변경하나? | `core/service/*Service.java` | 소유권·참조·상태 검사와 저장, 이벤트 적재 |
| 누가 요청하나? | `api/controller`, `api/listener`, 스케줄러 | 사용자 요청인지, 다른 서비스 이벤트인지, 예약 실행인지 |
| 외부 호출은 어떻게 엮나? | `api/application/*Facade.java` | 여러 단계를 조율하고 외부 호출·DB 변경을 구분 |
| 실제로 어디에 저장하나? | `@Table`, `@Document`, `resources/db/migration` | Java 필드명과 DB 컬럼명이 다를 수 있음 |
| 어떤 데이터를 전달하나? | `common/kafka/.../payload` | 저장 엔티티 전체가 아니라 이벤트에 담은 필드만 전달 |
| 어떤 사례를 검증했나? | `src/test`, `src/integrationTest`, `e2e/src/test` | 테스트가 다루는 입력·결과까지만 보장 범위를 해석 |

예를 들어 `GameProject.sellerId`는 DB에서 `workspace_id`에 저장된다. `Submission.metadataRevisionId`가 이벤트를 건너면 `metadataRevision`이라는 이름으로 등장해도 값은 여전히 이력 **ID**다. [식별자·버전 비교](../domain-glossary.md#identifiers)를 함께 읽는다.

## 현재 구현을 이해할 때 남겨둘 경계

- 기존 프로젝트 단위 심사(`ReviewRequest`)와 현재 제출물 단위 심사(`ReviewCase`)가 함께 있다. 새 Studio는 상세정보·파일과 심사 요청·출시 요청만 관리한다. Project 승인·출시 상태와 공개본은 관리하지 않는다.
- `PUBLISHED`는 상점 자료에서는 “확정됨”, 릴리스에서는 “해당 채널에 공개됨”이다. 일반 상품의 판매는 LIVE 릴리스 반영으로 시작한다. DEMO·BUNDLE의 일반 구매는 제한된다.
- 주문 취소 API와 결제 환불 API는 현재 연결이 완성되어 있지 않다. 환불 흐름은 Payment에서 시작한다. [Q9](open-questions.md#q9)
- 파일 검증·공개 전 점검은 게임을 실제 플레이해 검증하는 것과 다르다. [Q10](open-questions.md#q10)
- 현재 정산은 고객 결제액을 기준으로 플랫폼 수수료·판매자 몫을 계산하고 PG 비용은 플랫폼 잔여액에서 부담한다. 환불·할인 부담·재마감은 후속 범위다. [정산 예시](models/settlement.md#money-example), [Q12](open-questions.md#q12)
- 외부 PG·등급 기관·세금계산서 연동에는 대역 구현이 있다. DB의 성공 상태가 실제 외부 기관의 처리 완료를 증명하는 것은 아니다. [외부 연동 범위](../services.md#외부-연동-대역)

## 조사 범위와 유지 방법

`apps/*/core/domain`의 저장 모델 **40개와 직접 선언 속성 464개**를 대상으로 모델 사전을 작성했다. 공통 전달 기록 2개, 주요 입력·결과 record, 열거형의 의미도 설명한다. 저장 모델 중 업무 감사 기록은 [공통 페이지](models/shared.md)에 모았다. 모든 Controller DTO, Repository 인터페이스, 설정·지표·정렬 유틸리티, 프레임워크 소유 OAuth 테이블을 별도 업무 모델로 세지는 않았다.

근거는 로컬 모델·서비스·리스너·스키마와 기존 테스트 소스다. 이번 문서 작업의 검증은 링크·앵커·모델/필드 누락 확인이며 애플리케이션 테스트를 새로 실행한 결과가 아니다. 향후 모델 필드를 바꾸면 해당 표와 생성·사용 설명을 함께 고친다. 지원하지 않는 기능을 enum 값이나 필드의 존재만으로 “구현됨”이라고 쓰지 않는다.
