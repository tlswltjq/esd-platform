# 창작·심사·출시

[용어집](../domain-glossary.md) · [업무 흐름](workflows.md#publishing-flow) · [미결 질문](open-questions.md)

창작자는 프로젝트 안에 자료와 파일을 준비하고, 확정한 묶음으로 심사를 요청한다. 심사자는 유형별 사건을 처리하며, 모든 출시 관문이 승인되면 창작자가 릴리스를 공개할 수 있다. 상품의 판매 가능 여부는 Catalog가 관리한다.

아래 **업무 규칙 제안**은 검토할 업무 조건이다. **현재 구현**과 테스트로 확인된 범위를 별도로 적었으며, 합의 상태의 뜻은 [용어집](../domain-glossary.md#읽는-순서와-합의-상태)을 따른다.

<a id="game-project"></a>
## 1. 게임 프로젝트 — Game Project (`GameProject`)

**업무 정의·맥락:** 창작자가 출시 대상을 등록하고 자료·빌드·상품 관계를 관리하는 단위다. Studio가 소유 워크스페이스와 작성 책임을 관리한다. 권장 표현은 “프로젝트를 등록한다”; 구매 화면의 “상품”과 혼용하지 않는다.

**관계·경계:** 프로젝트는 자료 이력·빌드·제출물·릴리스의 기준이며, 구매용 Product와 `productCode`로 연결된다. 프로젝트가 있어도 Catalog 상품이 아직 없을 수 있다. BASIC·DEMO·DLC·EDITION·BUNDLE은 프로젝트의 상품 종류다.

**행위·규칙 제안:** 창작자는 자신의 워크스페이스에서 중복되지 않는 코드로 등록하고, 관련 프로젝트를 지정한다. 제출·출시 여부는 해당 Submission·Release로 설명한다.

**현재 구현:** `sellerId` 필드가 DB의 `workspace_id`에 대응한다. DEMO·DLC·EDITION의 부모는 같은 소유자의 BASIC이며, EDITION에는 이름이 필요하다. DEMO 가격은 0, BUNDLE 구성은 중복 없이 최소 2개다. `GameProject.status`의 `DRAFT → SUBMITTED → APPROVED/REJECTED`는 기존 프로젝트 단위 심사 경로다. 새 Submission 경로의 준비 상태를 이 값만으로 판단할 수 없다.

**사례:** 같은 창작자의 기본 게임에 DLC 프로젝트를 연결할 수 있다. 다른 창작자의 기본 게임을 부모로 연결하는 요청은 거절된다.

**근거:** [GameProject](../../apps/studio/src/main/java/com/stove/studio/core/domain/GameProject.java), [GameProjectService](../../apps/studio/src/main/java/com/stove/studio/core/service/GameProjectService.java). [GameProjectServiceTest](../../apps/studio/src/integrationTest/java/com/stove/studio/core/service/GameProjectServiceTest.java)의 `productFamilyRequiresOwnedRelations`, `duplicateProductCodeIsRejected`가 관계·중복 규칙을 확인한다.

**합의 상태:** 초안. 기존 프로젝트 상태의 역할과 판매자 명칭은 [Q2](open-questions.md#q2), [Q8](open-questions.md#q8).

<a id="product"></a>
## 2. 상품 — Product (`Product`)

**업무 정의·맥락:** 구매자가 주문할 대상을 가격·판매 상태와 함께 관리하는 단위다. Catalog가 원본을 책임지고 Store는 진열·검색용 사본을 제공한다. 권장 표현은 “상품을 조회한다·구매한다”; 작성 중인 GameProject와 구별한다.

**관계·경계:** Catalog가 발급하는 `productId`로 주문·이용권·정산이 연결된다. Studio 프로젝트의 `gameId`, 공통 연결 값 `productCode`, 공개 릴리스의 `currentReleaseId`·`currentBuildId`도 보관한다. 상품 ID와 게임 버전은 별개다.

**행위·규칙 제안:** 승인된 공개 자료를 상품에 반영하고, 구매 시점의 가격과 판매 가능 여부를 서버가 판정한다. 과거 주문의 금액은 상품 변경으로 바꾸지 않는다.

**현재 구현:** `ReleasePublished` 수신 시 코드 기준으로 생성/갱신하고 자료·가격·빌드 참조를 함께 적용한다. 기존 `ReviewApproved`도 상품을 생성할 수 있으나 공개 릴리스 없는 상품은 판매를 시작할 수 없다. 종류별 제약은 [판매 상태](#product-status)를 따른다.

**사례:** 새 LIVE 릴리스를 반영해도 같은 `productCode`의 기존 `productId`를 유지한다. 심사 승인만 받은 상품을 공개 릴리스 없이 판매 시작하는 요청은 거절된다.

**근거:** [Product](../../apps/catalog/src/main/java/com/stove/catalog/core/domain/Product.java), [ProductCommandService](../../apps/catalog/src/main/java/com/stove/catalog/core/service/ProductCommandService.java). [ProductTest](../../apps/catalog/src/test/java/com/stove/catalog/core/domain/ProductTest.java)의 `reviewAloneCannotOpenSale`, [ProductCommandServiceTest](../../apps/catalog/src/integrationTest/java/com/stove/catalog/core/service/ProductCommandServiceTest.java)의 `releaseProjectsProductFamily`, `reReviewUpdatesInPlaceAndRepublishes`.

**합의 상태:** 초안. 공개와 자동 판매의 정책은 [Q3](open-questions.md#q3).

<a id="store-page-revision"></a>
## 3. 상점 자료 이력 — Store Page Revision (`StorePageRevision`)

**업무 정의·맥락:** 창작자가 심사와 공개에 사용할 소개·이미지·지원 정보·정책 등의 특정 판본이다. Studio가 작성과 확정을 책임진다. 권장 표현은 “자료를 작성한다·확정한다”; 코드의 `PUBLISHED`는 이 문맥에서 “확정됨”으로 읽는다.

**관계·경계:** 한 프로젝트에 여러 이력이 있으며 Submission은 하나의 자료 **ID**를 선택한다. `revisionNo`는 이력 순번이다. Store의 현재 진열 문서와는 별개이고, 가격·등급 자료 이력도 별도로 선택한다.

**행위·규칙 제안:** 초안은 편집하고, 확정 후 수정은 새 이력으로 작성한다. 심사는 확정된 내용을 기준으로 진행한다.

**현재 구현:** `DRAFT → PUBLISHED`. 생성 시 `draft=false`이면 바로 확정 상태다. 확정할 때 같은 프로젝트에 업로드한 스크린샷과 커버가 필요하며, 아이콘을 지정하면 그것도 업로드된 파일인지 확인한다. 제출 시에도 이미지 저장 여부를 다시 검사한다. 확정된 이력은 수정하거나 다시 `publish()`할 수 없다. Submission은 `PUBLISHED`만 받는다. 이 행위만으로 공개 릴리스나 상점 노출이 만들어지지는 않는다. 자료의 `pricesJson`과 실제 출시 가격의 `PricingRevision`이 공존한다([Q7](open-questions.md#q7)).

**사례:** 상점 자료 1차를 확정해 제출한 뒤 설명 변경은 2차에 작성한다. 제출된 1차 내용을 직접 수정하는 요청은 거절된다.

**근거:** [StorePageRevision](../../apps/studio/src/main/java/com/stove/studio/core/domain/StorePageRevision.java), [RevisionService](../../apps/studio/src/main/java/com/stove/studio/core/service/RevisionService.java), [SubmissionService](../../apps/studio/src/main/java/com/stove/studio/core/service/SubmissionService.java). [StorePageRevisionTest](../../apps/studio/src/test/java/com/stove/studio/core/domain/StorePageRevisionTest.java)의 `draftBecomesImmutableRevision`.

**합의 상태:** 초안. 표준 동사와 필드 명명은 [Q1](open-questions.md#q1), [Q7](open-questions.md#q7).

<a id="game-build"></a>
## 4. 게임 빌드 — Game Build (`GameBuild`)

**업무 정의·맥락:** 특정 제품 버전과 실행 환경에 제공할 파일 산출물 및 그 검증 기록이다. Studio가 등록·검증을 책임지고 실제 파일은 오브젝트 저장소에 보관한다. “빌드를 업로드한다·검증한다”로 표현한다.

**관계·경계:** `buildId`는 산출물 식별자, `version`은 제품 버전, `buildNumber`는 빌드 추적 번호다. 같은 제품 버전에 여러 OS·아키텍처·델타 빌드가 있을 수 있다. 파일 검증 통과와 BUILD_QA 심사 승인은 각각 별도 조건이다.

**행위·규칙 제안:** 창작자 또는 프로젝트 범위 CI가 업로드하고, 검증을 통과한 동일 프로젝트 빌드만 제출한다. 델타에는 같은 대상 환경의 전체 빌드를 함께 제공한다.

**현재 구현:** `UPLOADING → PROCESSING → VALIDATED/FAILED`; 업로드 만료 또는 보존 처리로 `RETIRED`가 될 수 있다. SHA-256·악성코드 검사 포트·ZIP 내용과 manifest를 검사한다. 제출의 기본 빌드는 전체 파일이어야 하며, 묶음 안 제품 버전은 같고 OS·아키텍처·델타 출발 버전 조합은 중복할 수 없다. 업로드 멱등키는 `(gameId, idempotencyKey)`로 관리한다. 구 경로의 버전 중복 검사와 현재 DB 제약을 혼동하지 않는다.

**사례:** 제품 버전 `1.2.0`의 Windows 전체 파일과 `1.1.0 → 1.2.0` 델타를 함께 제출할 수 있다. 전체 파일 없이 델타만 제출하거나 서로 다른 대상 제품 버전을 섞으면 거절된다. Mac/Linux 파일의 실제 검증 지원은 [Q10](open-questions.md#q10) 참고.

**근거:** [GameBuild](../../apps/studio/src/main/java/com/stove/studio/core/domain/GameBuild.java), [BuildValidationService](../../apps/studio/src/main/java/com/stove/studio/core/service/BuildValidationService.java), [SubmissionService](../../apps/studio/src/main/java/com/stove/studio/core/service/SubmissionService.java), [업로드 스키마](../../apps/studio/src/main/resources/db/migration/V5__build_upload_lifecycle.sql). [SubmissionBuildSetTest](../../apps/studio/src/test/java/com/stove/studio/core/service/SubmissionBuildSetTest.java)의 `acceptsMultipleOsAndDeltaWithFullFallback`, `rejectsMixedVersionsAndDeltaPrimary`는 제출 묶음의 규칙을 검증하며 실제 OS 실행 검증은 하지 않는다.

**합의 상태:** 재검토 필요. 플랫폼 검증의 의도와 범위는 [Q10](open-questions.md#q10).

<a id="submission"></a>
## 5. 심사 제출물 — Submission (`Submission`)

**업무 정의·맥락:** 창작자가 특정 자료와 빌드 묶음에 대해 심사를 요청한 단위다. Studio가 제출 시 선택한 자료·산출물을 고정한다. 권장 표현은 “자료와 빌드 묶음을 제출한다”; 프로젝트 등록이나 파일 업로드와 구별한다.

**관계·경계:** 상점·가격·등급 이력 ID, 기본 빌드 ID와 추가 빌드 집합을 참조한다. Review에는 스냅샷을 전달하며 유형별 ReviewCase가 생긴다. Studio에는 같은 유형의 SubmissionGate가 생긴다. 제출 대상 참조는 고정되지만 심사 결과·출시 상태는 이후 바뀐다.

**행위·규칙 제안:** 같은 프로젝트의 확정 자료와 검증된 빌드를 제출하고, 승인된 그 묶음을 출시한다. 내용을 바꾸려면 새 자료·빌드를 선택한 새 제출물을 만든다. 같은 자료의 판단에 이의를 제기할 때는 재검토를 요청한다.

**현재 구현:** 제출 시 상점 자료 확정·업로드 이미지·프로젝트 일치·활성 등급 정책·빌드 집합을 검사한다. 시작 상태는 `SUBMITTED`; 수정 요청 시 `CHANGES_REQUESTED`, 모든 관문 승인 시 `READY_FOR_RELEASE`, LIVE 공개 시 `RELEASED`다. 재검토 이벤트로 `CHANGES_REQUESTED → SUBMITTED`가 가능하다. `sequenceNo`는 프로젝트별 제출 차수다.

**사례:** 새 설명과 새 빌드를 심사받으려면 새 Submission을 만든다. `PROCESSING` 빌드나 다른 프로젝트의 가격 이력을 선택한 제출은 거절된다. 수정 요청 상태에 뒤늦은 승인만 도착해서 출시 준비 상태로 바뀌지는 않는다.

**근거:** [Submission](../../apps/studio/src/main/java/com/stove/studio/core/domain/Submission.java), [SubmissionService](../../apps/studio/src/main/java/com/stove/studio/core/service/SubmissionService.java), [SubmissionBuild](../../apps/studio/src/main/java/com/stove/studio/core/domain/SubmissionBuild.java). [SubmissionTest](../../apps/studio/src/test/java/com/stove/studio/core/domain/SubmissionTest.java)의 `changesRequestedIsTerminalForTheSnapshot`, `submittedCannotBeReleasedDirectly`; 빌드 집합은 [SubmissionBuildSetTest](../../apps/studio/src/test/java/com/stove/studio/core/service/SubmissionBuildSetTest.java). 테스트 이름의 “terminal”은 재검토 이벤트 경로까지 금지한다는 의미로 읽지 않는다.

**합의 상태:** 초안. 재검토와 새 제출의 구분 및 관문 동기화는 [Q4](open-questions.md#q4).

<a id="review-case"></a>
## 6. 심사 사건 — Review Case (`ReviewCase`)

**업무 정의·맥락:** 제출물의 한 심사 유형을 담당자가 판단하고 증빙·사유·처리 이력을 남기는 단위다. Review가 의미를 책임진다. “심사 사건을 배정한다·승인한다·수정 요청한다”로 표현한다. “심의”는 기존 API 표현이며, 이 문서에서는 등급 외 검토까지 포괄해 “심사”를 쓴다.

**관계·경계:** `(submissionId, reviewType)`마다 한 사건이 있다. 유형은 `RATING`, `STORE_PAGE`, `BUILD_QA`, `LEGAL`, `SDK_COMPLIANCE`, `COMMERCIAL` 여섯 가지다. 사건 하나의 승인과 제출물 전체의 출시 준비는 다르다. 기존 `ReviewRequest`는 프로젝트 단위 호환 경로다.

**행위·규칙 제안:** 심사자는 고정된 자료를 검토하고 유형에 맞는 근거로 결정한다. 수정 요청은 외부 피드백, 내부 검토 사항은 내부 메모로 기록한다. 같은 자료에 대한 재검토는 이력과 회차를 보존한다.

**현재 구현:** 일반 사건은 `REQUESTED → APPROVED/CHANGES_REQUESTED/BLOCKED/CANCELLED/EXPIRED`. GRAC 등급 경로에는 `EXTERNAL_SUBMITTED`가 추가된다. `CHANGES_REQUESTED/BLOCKED/EXPIRED`는 사유 있는 이의 제기로 `REQUESTED`로 돌아가며 `reviewRound`가 증가한다. 승인은 열린 사건에서만 가능하다. 자체등급은 정책이 정한 ALL/12/15와 내부 인증 기록, GRAC은 18세 결정에 맞는 외부 접수·인증 증빙이 필요하다. 이는 현재 코드의 정책이며 실제 기관 절차를 확정하는 문서가 아니다.

**사례:** BUILD_QA를 승인해도 LEGAL이 남아 있으면 전체 출시는 대기한다. 자체등급 경로에서 18세 승인을 요청하거나, 외부 접수 증빙 없이 GRAC 승인을 요청하면 거절된다. 현재 승인 시 체크리스트 완료나 담당자 일치 검사는 없으므로 필수 승인 조건으로 단정하지 않는다.

**근거:** [ReviewCase](../../apps/review/src/main/java/com/stove/review/core/domain/ReviewCase.java), [SubmissionReviewService](../../apps/review/src/main/java/com/stove/review/core/service/SubmissionReviewService.java). [ReviewCaseTest](../../apps/review/src/test/java/com/stove/review/core/domain/ReviewCaseTest.java)의 `externalRatingRequiresCompleteEvidence`, `appealReopensBlockedReview`; [SubmissionReviewServiceTest](../../apps/review/src/test/java/com/stove/review/core/service/SubmissionReviewServiceTest.java)의 `rejectsRatingDifferentFromPolicyDecision`.

**합의 상태:** 재검토 필요. 승인 조건과 운영 상태 전달은 [Q4](open-questions.md#q4), 기존 경로와 명칭은 [Q2](open-questions.md#q2).

<a id="submission-gate"></a>
## 7. 출시 관문 — Submission Gate (`SubmissionGate`)

**업무 정의·맥락:** Studio가 제출물의 출시 준비를 판단하기 위해 유지하는 유형별 심사 결과 기록이다. 권장 표현은 “관문에 승인을 반영한다”, “모든 관문을 통과했다”. ReviewCase 자체를 “관문”이라고 부르지 않는다.

**관계·경계:** 제출 시 여섯 관문을 만들고 Review 이벤트를 반영한다. 심사자의 배정·체크리스트·내부 메모는 포함하지 않는다. 사건은 Review의 업무 원본, 관문은 Studio의 출시 판단용 투영이다.

**행위·규칙 제안:** 승인된 자료·빌드가 제출물과 일치할 때 해당 관문에 반영하고 모든 필수 유형 승인 후 출시를 허용한다.

**현재 구현:** `PENDING → APPROVED/CHANGES_REQUESTED`; 재검토 시 `CHANGES_REQUESTED → PENDING`. 승인 이벤트의 기본 빌드·자료 이력 ID를 제출물과 대조한다. 관문 전체가 `APPROVED`이면 제출물을 준비 상태로 바꾼다. ReviewCase의 `BLOCKED/CANCELLED/EXPIRED`는 관문에 대응 상태가 없고 현재 해당 변경 이벤트도 발행하지 않는다.

**사례:** 여섯 유형 중 다섯 유형만 승인되면 출시할 수 없다. 다른 자료 이력에 대한 승인을 현재 제출물에 반영하는 요청은 거절된다. “심사 만료”를 “Studio에서도 만료 상태”라고 설명하면 현재 구현과 다르다.

**근거:** [SubmissionGate](../../apps/studio/src/main/java/com/stove/studio/core/domain/SubmissionGate.java), [SubmissionReviewProjectionService](../../apps/studio/src/main/java/com/stove/studio/core/service/SubmissionReviewProjectionService.java). [SubmissionTest](../../apps/studio/src/test/java/com/stove/studio/core/domain/SubmissionTest.java)는 제출 상태의 일부 전이만 검증한다. [TrackACreatorFlowTest](../../e2e/src/test/java/com/stove/e2e/TrackACreatorFlowTest.java)는 미승인 출시 거절·수정 요청 반영·전체 승인 후 출시를 검증한다. 잘못된 승인 스냅샷 대조의 직접 검증 공백은 [Q11](open-questions.md#q11)에 기록한다.

**합의 상태:** 재검토 필요. [Q4](open-questions.md#q4), [Q11](open-questions.md#q11).

<a id="release"></a>
## 8. 릴리스 — Release (`Release`)

**업무 정의·맥락:** 승인된 자료와 빌드 묶음을 특정 채널에 공개하는 단위다. Studio가 예약·공개·이전 내용 복원을 책임진다. “릴리스를 예약한다”, “LIVE에 공개한다”로 표현하고 상점 자료 확정과 구별한다.

**관계·경계:** Submission의 자료 이력과 기본 빌드를 참조하고, 배포 빌드 집합은 제출물에서 가져온다. 채널은 DEV·TEST·STAGE·LIVE다. 각 채널의 `PUBLISHED`가 구매자 대상 LIVE 공개를 뜻하지는 않는다.

**행위·규칙 제안:** 출시 준비된 제출물을 즉시 또는 지정 시각에 공개한다. 공개 직전 파일을 확인하고, 이전 공개 내용을 복원해도 새로운 공개 이력을 남긴다.

**현재 구현:** 생성은 `READY_FOR_RELEASE` 제출물만 가능하다. `SCHEDULED → PUBLISHED/CANCELLED/SMOKE_TEST_FAILED`. 공개는 예약 시각 도달과 smoke test 통과가 필요하다. 현재 smoke test는 모든 빌드의 `VALIDATED` 상태와 저장 객체 크기를 확인하며 게임 실행 검사가 아니다. 채널 승격은 DEV→TEST→STAGE→LIVE 순서이고 LIVE 공개만 `ReleasePublished`를 내보내며 제출물을 `RELEASED`로 바꾼다. 롤백은 과거 `PUBLISHED` 대상의 내용으로 새 `ROLLBACK` 릴리스를 만든다. 예약 취소·재예약은 `SCHEDULED`에서만 가능하다.

**사례:** 내일 공개할 릴리스를 오늘 강제로 공개하거나 smoke test 실패 상태로 공개할 수 없다. 과거 릴리스로 롤백해도 기존 이력을 삭제하지 않는다. 현재 롤백 서비스는 smoke 실패 후에도 `ReleaseRolledBack`을 적재할 수 있어 성공 판정은 [Q10](open-questions.md#q10)의 후속 확인 대상이다.

**근거:** [Release](../../apps/studio/src/main/java/com/stove/studio/core/domain/Release.java), [ReleaseService](../../apps/studio/src/main/java/com/stove/studio/core/service/ReleaseService.java), [ReleaseSmokeTestService](../../apps/studio/src/main/java/com/stove/studio/core/service/ReleaseSmokeTestService.java). [ReleaseTest](../../apps/studio/src/test/java/com/stove/studio/core/domain/ReleaseTest.java)의 `smokeTestGatesPublication`은 공개 전 검사 통과 조건을 검증한다. [TrackACreatorFlowTest](../../e2e/src/test/java/com/stove/e2e/TrackACreatorFlowTest.java)의 `publishesPatchAndRollsBack`, `promotesChannelsAndManagesSchedule`은 정상 롤백·채널 승격·예약 변경·취소를 검증한다. 실패 롤백 검증 공백은 [Q11](open-questions.md#q11)에 남긴다.

**합의 상태:** 재검토 필요. 공개·판매 연동 [Q3](open-questions.md#q3), 검증·롤백 의미 [Q10](open-questions.md#q10).

<a id="product-status"></a>
## 9. 상품 판매 상태 — Product Status (`ProductStatus`)

**업무 정의·맥락:** 신규 주문을 허용하는지 나타내는 상품 상태다. Catalog가 관리하고 Store가 진열에 반영한다. “판매를 시작한다·중지한다·재개한다”로 표현하며, “자료를 공개한다”와 구별한다.

**관계·경계:** 공개 릴리스는 판매 시작의 선행 조건이다. 판매 중지와 기존 구매자의 이용권 회수는 별도 행위다. 현재 판매 중지에서 License 회수 이벤트가 발생하지 않으며 Download는 보유권과 공개 릴리스를 확인한다.

**행위·규칙 제안:** 판매 가능한 종류와 공개 자료가 준비된 상품만 신규 주문에 허용한다. 판매 중지 뒤 재개 조건을 명시한다.

**현재 구현:** `ON_SALE`만 구매 가능하다. LIVE 릴리스 반영은 일반 상품을 자동 `ON_SALE`로 바꾸고, DEMO·BUNDLE은 `APPROVED`로 둔다. 운영 판매 시작은 공개 릴리스가 있고 상태가 `APPROVED` 또는 `SUSPENDED`여야 하며 DEMO·BUNDLE은 거절한다. `suspend()`는 `SUSPENDED`로 바꾼다. `DRAFT/REVIEWING/APPROVED/SUSPENDED/CLOSED`는 구매 불가이며, `CLOSED`로 전환하는 업무 경로는 현재 Product에 없다. 새 릴리스 반영이 `SUSPENDED`도 다시 판매 상태로 바꾸는 점은 정책 확인이 필요하다.

**사례:** 공개된 BASIC 상품은 주문할 수 있다. 공개된 BUNDLE도 구성품 권한 지급 경로가 없으므로 일반 주문을 받을 수 없다. 이 구현 제한만으로 “번들은 앞으로도 판매하지 않는다”는 정책을 합의한 것으로 읽지 않는다.

**근거:** [ProductStatus](../../apps/catalog/src/main/java/com/stove/catalog/core/domain/ProductStatus.java), [Product](../../apps/catalog/src/main/java/com/stove/catalog/core/domain/Product.java). [ProductTest](../../apps/catalog/src/test/java/com/stove/catalog/core/domain/ProductTest.java)의 `releaseMakesProductPurchasable`, `suspendedProductIsNotPurchasable`; [ProductCommandServiceTest](../../apps/catalog/src/integrationTest/java/com/stove/catalog/core/service/ProductCommandServiceTest.java)의 `releaseProjectsProductFamily`, `suspendedProductCanResume`. 종류·빌드 명세는 [P2 문서](../p2-product-family-and-builds.md).

**합의 상태:** 재검토 필요. 자동 판매·정지 유지 [Q3](open-questions.md#q3), 종류별 지급 정책 [Q5](open-questions.md#q5).
