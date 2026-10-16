# 업무 코드의 기술 책임 혼합 점검

[문서 목록](README.md) · [유스케이스 규칙](use-case-conventions.md) · [설계 결정](decisions.md)

2026-10-16 작업 트리의 재작성 완료 모듈 `auth`, `gateway`, `studio`, `review`, `catalog`, `store`, `order`와 관련 공통 코드를 정적으로 조사했다. Order의 미커밋 구현을 포함한다. 미완료 Payment·License·Download·Settlement는 판단 기준에 포함하지 않았다. 아래 분석은 입력 변환을 수정하기 전의 상태다. 이후 구현 범위와 검증은 문서 끝의 수정 기록에 구분한다.

## 판단 기준과 규모

`core.service`는 애플리케이션 유스케이스 계층이다. 하나의 업무에 필요한 조회·잠금·트랜잭션·도메인 호출·Inbox·Outbox를 조율할 수 있다. `core.domain`의 모델은 상태 전이와 불변식을 담당한다. **Inbox를 서비스에서 확인한다는 사실만으로 경계 위반은 아니다.**

현재 [결정 6](decisions.md#6-트랜잭션과-멱등-가드는-서비스가-소유한다)과 [아키텍처 검사](../common/archunit/src/main/java/com/stove/common/archunit/ModuleHygieneRules.java)는 서비스가 트랜잭션과 멱등 가드를 소유하도록 요구한다. 리스너로 가드를 옮기는 것은 현재 규칙과 다르다. 가드·업무 변경·Outbox가 필요한 경우 이들은 같은 DB 트랜잭션에 있어야 한다.

조사한 `core.service` 파일은 **28개**다. `@Service` 업무 클래스 27개와 `UploadFiles` 보조 클래스 1개다. 아래 여섯 유형 중 하나 이상이 있는 **14개 파일**을 분리·개선 후보로 집계했다. 이는 아키텍처 위반 14건이라는 뜻이 아니다. 포트 호출, JPA, Spring Data 조회, 단순 이벤트 기록 자체는 후보 수에 넣지 않았다.

| 모듈 | 업무 클래스 / 보조 클래스 | 후보 파일 | 주요 내용 |
|---|---:|---:|---|
| Auth | 3 / 0 | 0 | 비밀번호 암호화·토큰 폐기는 이 모듈의 책임이며 추상화로 호출 |
| Gateway | 0 / 0 | 0 | 필터·BFF·세션 등 기술 처리가 모듈 자체의 책임 |
| Studio | 10 / 1 | 7 | JSON, 파일·이미지 처리, 공개 URL, 잠금 중 스토리지 조회 |
| Review | 4 / 0 | 2 | 이벤트 봉투 검증, 스냅샷 JSON |
| Catalog | 2 / 0 | 2 | 이벤트 봉투 검증, 스냅샷 JSON |
| Store | 4 / 0 | 1 | 이벤트 봉투 검증, DB 예외별 재시도 |
| Order | 4 / 0 | 2 | 이벤트 봉투 검증, DB 예외별 재시도 |
| 합계 | 27 / 1 | 14 | 같은 파일이 여러 유형에 해당하면 한 번 집계 |

| 기술 처리 유형 | 파일 수 | 대상 |
|---|---:|---|
| Kafka `EventEnvelope`를 유스케이스 입력으로 받고 검증 | 4 | `ReceiveProjectReview`, `PublishProjectRelease`, `ApplyCatalogProduct`, `ApplyPaymentResult` |
| `ObjectMapper`로 저장 스냅샷 직렬화·역직렬화 | 6 | `RequestProjectReview`, `ReadProjects`, `ReceiveProjectReview`, `ReadProjectReviews`, `PublishProjectRelease`, `ReadPublishedProducts` |
| Spring/Hibernate DB 예외를 나열하며 새 트랜잭션 재시도 | 2 | `ApplyCatalogProduct`, `CreateOrder` |
| 임시 파일·ZIP·체크섬·이미지 처리 | 3 | `UploadFiles`, `RegisterBuild`, `UploadThumbnail` |
| 공개 HTTP 경로를 조립하거나 분해 | 2 | `UploadThumbnail`, `UpdateStorePage` |
| DB 트랜잭션과 Project 잠금 안에서 실제 스토리지 조회 | 3 | `RequestProjectReview`, `RequestProjectRelease`, `UpdateStorePage` |

별도로 Inbox 사용은 **5개 서비스**, Outbox 사용은 **6개 서비스**다. 도메인 모델이 Inbox·Outbox를 직접 호출하는 곳은 없었다. 이 사용 횟수를 위 표의 개선 후보 수와 합산하지 않는다.

## 1. 이벤트 입력 경계가 일관되지 않다

[Review 접수](../apps/review/src/main/java/com/stove/review/core/service/ReceiveProjectReview.java), [Catalog 출시](../apps/catalog/src/main/java/com/stove/catalog/core/service/PublishProjectRelease.java), [Store 반영](../apps/store/src/main/java/com/stove/store/core/service/ApplyCatalogProduct.java), [Order 결과 반영](../apps/order/src/main/java/com/stove/order/core/service/ApplyPaymentResult.java)은 봉투와 본문의 ID·종류·메시지 키 일치를 서비스에서 확인한다. 업무 호출부가 Kafka 봉투를 만들어야 하고, 이벤트 형식 검증과 업무 규칙이 한 메서드에 섞인다.

반면 [Studio 결과 리스너](../apps/studio/src/main/java/com/stove/studio/api/listener/ProjectResultListener.java)는 봉투 ID·종류와 본문의 일부 필드만 서비스에 넘긴다. **본문 `eventId`, `occurredAt`, `productCode`를 결과 처리에 넘기지 않아 헤더/본문 ID 일치와 키/상품 코드 일치를 검사하지 않는다.** 현재 이벤트 종류는 헤더 분기로 선택한 구체적인 이벤트 타입의 상수이므로, 종류 문자열만 비교하는 것으로 이 누락을 보완할 수 없다.

또한 [Studio 심사 결과 서비스](../apps/studio/src/main/java/com/stove/studio/core/service/review/RecordProjectReviewResult.java)와 [출시 결과 서비스](../apps/studio/src/main/java/com/stove/studio/core/service/release/RecordProjectReleaseResult.java)는 요청 ID·Project·버전을 비교하지만, 외부 결과의 필수 값과 길이를 공통된 입력 모델에서 검증하지 않는다. 이는 다른 모듈이 판정할 업무를 대신 수행하는 문제가 아니라, 자기 요청에 반영할 외부 계약의 검증 문제다.

제안:

- 리스너 또는 리스너가 사용하는 입력 변환기에서 역직렬화와 봉투/본문/키 검증을 끝낸다.
- 유스케이스에는 전송 방식과 무관한 이벤트 식별 정보와 검증된 입력 값을 넘긴다. Kafka의 원문 payload는 넘기지 않는다.
- 요청 연결·소유자·금액·통화 일치와 상태 전이 가능 여부는 유스케이스와 도메인에서 확인한다.
- 이 변환 과정에서 Studio의 누락된 계약 검증도 보완한다. 정상 계약과 잘못된 계약을 구분하는 동작 변경이므로 OCI 회귀 검증이 필요하다.

## 2. Inbox 중복 제거와 업무 중복 판정은 별개다

| 서비스 | 현재 처리 순서 | 의미 |
|---|---|---|
| `RecordProjectReviewResult` | Inbox → 요청 조회·결과 기록 | 같은 이벤트 ID면 내용 확인 전에 종료 |
| `RecordProjectReleaseResult` | Inbox → 요청 조회·결과 기록 | 같은 이벤트 ID면 내용 확인 전에 종료 |
| `ReceiveProjectReview` | 입력 검증 → Inbox → 기존 스냅샷 비교 | 같은 이벤트 ID의 변경된 스냅샷은 비교되지 않음 |
| `PublishProjectRelease` | 입력 검증 → 기존 요청 내용·ID 재사용 확인 → Inbox | 이미 저장된 출시 요청의 변경 내용을 거부 |
| `ApplyPaymentResult` | 입력 검증 → 주문 잠금·ID 재사용·기존 결과 비교 → Inbox | 같은 ID의 변경 결과와 다른 주문에 쓴 결과 ID를 거부 |

공통 [ProcessedEventGuard](../common/messaging/src/main/java/com/stove/common/messaging/inbox/ProcessedEventGuard.java)는 `(eventId, consumerGroup)`만 확인한다. 본문 내용의 동일성은 확인하지 않는다. 같은 이벤트 ID의 본문은 불변이라는 정상 전달 가정에서는 앞의 세 서비스도 중복을 안전하게 무시하지만, 잘못 재사용한 ID의 내용 차이는 발견하지 못한다.

Studio는 [기존 계획](studio-simplification-plan.md#요청-상태와-재요청)의 “처리된 요청의 뒤늦은 상충 결과와 중복 이벤트는 기존 기록·새 요청을 덮어쓰지 않는다”는 정책에 따라 이미 확정한 요청의 결과를 무시한다. 이를 Order와 같은 충돌 거부 정책으로 바꾸는 것은 별도의 업무 정책 변경이다. 리팩터링에 섞어 일괄 변경하지 않는다. Review는 새 이벤트 ID로 동일 요청을 재접수할 때 스냅샷 동일성을 확인하도록 이미 구현돼 있다.

**공통 Inbox 래퍼가 먼저 중복을 판단하고 모든 업무 호출을 생략하는 구조는 피한다.** Catalog·Order의 기존 내용 비교가 실행되지 않기 때문이다. 기술적 중복 제거는 공통 가드가 담당하고, 어떤 요청·결과가 같은 업무인지는 각 모델과 유스케이스가 판단해야 한다.

## 3. 도메인 값의 검증과 외부 타입 의존

[PaymentResult](../apps/order/src/main/java/com/stove/order/core/domain/PaymentResult.java)는 검증 없는 record이며 필수 값·금액·통화·최종 상태 등의 검증 대부분이 `ApplyPaymentResult`에 있다. `Order.apply()`는 주문과의 일치·기존 결과의 동일성·최종 상태는 검사한다. 그러나 코드 경로상 다른 호출부가 `eventId == null`인 결과를 직접 넘기면, 나머지 일치 조건을 만족한 상태에서 `PAID`로 바꾸면서 결과 ID는 비어 있을 수 있다. 현재 수신 경로는 서비스 검증이 막고 있어 외부 API에서 재현한 결함이라는 뜻은 아니다.

`PaymentResult`의 생성 시 필수 값과 값 자체의 유효성을 보장하고, `Order.apply()`에는 주문과 비교해야 알 수 있는 규칙을 남기는 것이 먼저다. 봉투의 메시지 키 비교는 이 값 객체의 책임이 아니다.

외부 이벤트·공유 스냅샷 타입에 직접 의존하는 도메인 모델은 **5개**다.

| 의존 형태 | 대상 | 판단 |
|---|---|---|
| 이벤트 전체를 생성 인자로 사용 | `ProjectReview.requested(ProjectReviewRequestedEvent, ...)`, `CatalogRelease.decided(ProjectReleaseRequestedEvent, ...)` | 이벤트 계약 변경이 엔티티 생성 코드까지 전달됨. 로컬 입력 값으로 변환할 우선 후보 |
| 공유 스냅샷 값 사용 | `CatalogProduct`, Catalog의 `PublishedProduct`, `StoreProduct` | 현재는 순수 데이터 record를 재사용하는 MVP 선택. 즉시 같은 필드의 모델을 복제할 필요는 없음 |

`ProjectSnapshot`과 `CatalogProductSnapshot` 자체에는 Kafka 호출이나 Inbox가 없다. 따라서 이 다섯 모델을 모두 동일한 심각도의 전송 기술 침투로 보지는 않는다. Review의 `ReviewBuildStorage`와 Store의 `CatalogProducts` 포트도 공유 스냅샷을 사용하므로, 로컬 모델로 전환할 때는 포트·조회·응답 변환까지 함께 검토해야 한다.

## 4. JSON 저장 형식이 업무 코드에 노출된다

Studio·Review·Catalog의 여섯 서비스가 불변 스냅샷을 JSON 문자열로 저장하거나 읽는다. `ObjectMapper`와 직렬화 예외 처리가 업무 흐름에 반복된다. 특히 `ReadProjects.snapshot()`은 조회 유스케이스가 다른 쓰기 유스케이스에 직렬화 도구까지 제공한다.

우선 모듈 안의 스냅샷 codec 또는 JPA converter 등으로 저장 형식 변환을 모을 수 있다. 외부 구현으로 둘 경우 기존 규약에 맞게 포트를 사용한다. DB 스키마·저장 JSON·과거 자료의 읽기·동일 스냅샷 비교 의미를 유지해야 한다. 세 모듈을 통합하는 범용 프레임워크부터 만들 필요는 없다.

## 5. 파일 처리와 공개 URL은 Studio에 집중돼 있다

[UploadFiles](../apps/studio/src/main/java/com/stove/studio/core/service/build/UploadFiles.java)는 임시 파일 생성·삭제, 스트림 복사, SHA-256, ZIP 항목 검사를 한다. [RegisterBuild](../apps/studio/src/main/java/com/stove/studio/core/service/build/RegisterBuild.java)와 [UploadThumbnail](../apps/studio/src/main/java/com/stove/studio/core/service/project/UploadThumbnail.java)은 파일 검사·저장·실패 시 정리·DB 갱신을 조율하며, 썸네일 서비스는 이미지 판독 구현까지 직접 포함한다.

크기 제한·허용 형식·소유권·등록 가능한 버전은 이 업무의 정책이다. 임시 디스크·이미지 판독·체크섬 계산 방식은 구현이다. 파일 준비 포트로 구현을 옮기고 준비 결과인 크기·형식·체크섬·저장 참조를 받아 업무 흐름을 진행할 수 있다. 저장 실패 정리와 DB 실패 시 저장 파일 삭제 순서는 보존해야 한다.

또한 `UploadThumbnail`은 `/api/v1/studio/assets/` URL을 직접 만들고, [UpdateStorePage](../apps/studio/src/main/java/com/stove/studio/core/service/project/UpdateStorePage.java)는 같은 경로를 기준으로 소속 자산을 확인한다. 공개 경로가 바뀌면 두 업무 서비스가 함께 바뀐다. 자산 소속 검증은 유지하되 URL 생성·해석은 자산 참조 포트/변환기로 모으는 것이 적절하다.

## 6. DB 재시도와 외부 I/O

`ApplyCatalogProduct`와 `CreateOrder`는 같은 네 종류의 Spring/Hibernate 예외를 나열하고 최대 세 번 새 트랜잭션을 시도한다. 재시도 가능한 DB 실패의 분류가 반복된다. 작은 공통 정책으로 추출할 수 있지만, 재시도는 실패한 트랜잭션을 끝낸 뒤 전체 단위로 수행해야 한다. `CreateOrder`의 기존 요청 재조회·경합한 요청 반환·Catalog 장애 시 기존 주문 반환은 주문 고유의 동작이므로 공통 재시도에 흡수하지 않는다.

JPA와 Spring Data는 [결정 4](decisions.md#4-도메인-객체와-엔티티를-분리하지-않는다)에 따라 허용돼 있다. `EntityManager`를 사용하는 세 서비스의 `persist/flush` 자체를 위반으로 세지 않았다. 신규 행을 기존 행에 merge하지 않기 위한 INSERT와 오류를 트랜잭션 안에서 드러내는 flush의 목적을 보존해야 한다.

Studio의 심사·출시 요청은 Project 잠금 뒤 `storage.size()`로 S3 HEAD 요청을 한다. `UpdateStorePage`도 잠금 안에서 `thumbnailDownload()`를 호출하며 실제 어댑터는 S3 HEAD 요청을 수행한다. 포트 사용은 올바르지만 스토리지 응답을 기다리는 동안 DB 잠금·커넥션이 유지된다. 이는 경계 위반보다는 트랜잭션 자원 사용 문제다.

추출만으로 이 문제는 해결되지 않는다. 먼저 불변 빌드/자산 참조를 읽고 외부 확인을 한 뒤, 짧은 쓰기 트랜잭션에서 Project 버전·선택 빌드·소유권을 다시 확인하는 방식이 가능하다. 그 사이 삭제를 허용할지 등 자산 수명 정책도 필요하다. 이 조회로 외부 저장소와 DB의 원자성이 생기는 것은 아니다.

## 권장 작업 순서

1. **Order를 기준 사례로 정리한다.** 입력 변환에서 봉투 검증을 분리하고 `PaymentResult`에 값 검증을 둔다. 잠금·동일 결과 확인·Inbox·flush 순서와 기존 정책을 유지한다. 처음부터 별도 처리 클래스와 래퍼를 의무적으로 추가하지 않는다.
2. **이벤트 수신 네 모듈을 맞춘다.** Studio의 입력 검증 누락을 보완하고 Review·Catalog·Store도 전송 방식과 무관한 입력으로 받는다. 중복·상충 결과의 정책은 모듈별로 유지하고 명시한다.
3. **스냅샷 JSON과 이벤트 전체를 받는 엔티티 두 곳을 정리한다.** 과거 저장 자료와 현재 공개 API·이벤트 계약은 유지한다.
4. **Studio의 파일·URL 구현을 분리하고 잠금 중 I/O를 줄인다.** 소유권·버전 재확인과 실패 시 정리를 함께 검증한다.
5. **중복 DB 재시도 정책만 작게 추출한다.** 각 유스케이스의 경합 처리 의미를 공통화하지 않는다.

구조 검사도 보강할 여지가 있다. 현재 `core_는_전송_기술을_모른다`는 Spring/Kafka SDK를 금지하지만 `com.stove.common.kafka.EventEnvelope`를 통한 의존은 잡지 않는다. 도메인 모델의 이벤트 전체 타입 의존과 업무 계층의 파일 구현도 검사하지 않는다. 구조를 정리한 범위부터 규칙을 추가하고, 순수 스냅샷 재사용과 JPA 예외는 구분한다.

실제 수정 시 OCI에서 기존 단위·통합·계약 검증에 더해 봉투/본문 불일치, 동일 ID의 변경된 본문, 모듈별 지연 결과 정책, Inbox·업무·Outbox 동시 롤백, 실패 트랜잭션 재시도, 업로드 실패 정리와 버전 경합을 확인한다. 이 분석 작업에서는 빌드·테스트·서비스 실행이나 동작 변경을 하지 않았다.

## 이벤트 수신 경계 수정 기록

2026-10-16 후속 구현에서는 이벤트 입력 경계를 대상으로 다음 변경을 적용했다.

| 모듈 | `api.listener` 변환기 | 서비스가 받는 값 |
|---|---|---|
| Studio | `ProjectResultMapper` | `ProjectReviewResult`, `ProjectReleaseResult` |
| Review | `ReviewSubmissionMapper` | `ReviewSubmission` |
| Catalog | `ReleaseSubmissionMapper` | `ReleaseSubmission` |
| Store | `CatalogProductMapper` | 기존 `CatalogProductSnapshot` |
| Order | `PaymentResultMapper` | 이벤트 종류 문자열과 `PaymentResult` |

- 모든 변환기는 정적 `from(...)`으로 봉투/본문 ID·종류·키를 검증한다. Studio는 빠져 있던 본문 ID·발생 시각·상품 코드와 결과 필수 값 검증을 추가했다.
- Review·Catalog의 모델 생성 인자에서 외부 이벤트 전체를 제거했다. 내부 입력은 생성 시 필수 값과 유효성을 검증하며 순수 공유 스냅샷 값은 재사용한다.
- `PaymentResult`도 생성 시 식별자·시각·금액·통화·최종 상태·실패 사유를 검증한다. 주문과의 연결·기존 결과 비교는 `Order`와 유스케이스에 남겼다.
- Inbox·행 잠금·Outbox·flush의 트랜잭션과 업무별 중복/지연 결과 정책을 유지했다. JSON 저장, 파일·URL 구현과 DB 재시도 추출은 후속 후보로 남긴다.
- `EventInputRules`를 다섯 모듈에 적용했다. core의 봉투 의존, 서비스의 외부 이벤트 입력, 도메인 모델의 외부 이벤트 의존을 금지한다. 발행 이벤트와 순수 공유 스냅샷 사용은 허용한다. `api.listener` 명명 검사에는 `Mapper`를 허용했다.

OCI에서 완료된 일곱 모듈의 단위·구조 검사 351건, 실제 DB·Kafka 통합 검사 72건, 공통 구조 규칙 검사 22건으로 **445건을 실행해 모두 통과**했다. 실패·오류·건너뜀은 0건이며 `--rerun-tasks`, `-Pstove.infraTest.maxParallel=1`, `--no-daemon`으로 실행했다. Studio의 네 결과 종류 변환과 잘못된 봉투의 서비스 호출 차단, 모델 값 검증, 기존 중복·지연·동시성·원자성·OpenAPI 회귀를 포함한다.

이어서 OCI에서 `scripts/verify-order.py`로 Auth·Gateway·Studio·Review·Catalog·Store·Order의 실제 프로세스를 실행해 **E2E 6단계를 모두 통과**했다. 실제 출시·Store 반영·주문 생성, Kafka 중단과 미발행 주문 이벤트 복구, 결제 결과 픽스처·잘못된 결과의 DLT 처리, Catalog 중단 중 조회, Order 재시작 후 결과 보존, Catalog 복구 후 새 주문 생성을 확인했다. 실제 Payment 업무는 후속 대상이며 결과 이벤트는 계약 픽스처로 검증했다.

증거는 OCI `/tmp/stove-order-check-g4enb3q8`의 앱 로그와 여섯 `junit-*` 디렉터리다. 각 XML에서 1건 실행·실패/오류/건너뜀 0건을 확인했다. 검증 종료 후 실행 중인 앱과 컨테이너는 0개이며 기존 데이터 볼륨은 보존했다.
