# 심사 모델

[전체 안내](../model-guide.md) · [Studio의 제출물](studio.md#submission)

Review는 “제출한 내용에 대해 누가 어떤 판단을 했는가”를 보관한다. 모두 MySQL 저장 모델이다. 같은 제출물을 읽더라도 심사자의 작업 상태와 Studio의 출시 준비 상태는 별도로 관리한다.

## SubmissionSnapshot — 심사 시점의 제출 사본

저장: `submission_snapshot`. [모델](../../../apps/review/src/main/java/com/stove/review/core/domain/SubmissionSnapshot.java)

| 속성 | 뜻과 사용처 |
|---|---|
| `submissionId` (`Long`) | Studio 제출물 ID를 그대로 저장 키로 사용 |
| `gameId`, `sellerId` (`Long`), `productCode` (`String`) | 프로젝트·판매자·공통 상품 코드 |
| `metadataRevision`, `pricingRevision`, `ratingRevision`, `buildId` (`Long`) | 심사 대상 이력·기본 빌드의 ID. `Revision`은 순번이 아님 |
| `title`, `shortDescription` (`String`) | 제출된 제목·짧은 설명 |
| `price` (`long`), `currency` (`String`) | 제출된 가격·통화 |
| `ratingPath`, `ratingPolicyVersion`, `ratingCountry` (`String`) | 등급 경로·정책·국가 |
| `recommendedRatingCode`, `ratingQuestionnaire` (`String`) | 추천 등급과 설문 JSON |
| `productVersion`, `buildVariantsJson` (`String`) | 게임 버전과 제출 빌드 목록 JSON |

**만들고 쓰는 때:** `SubmissionCreated`를 받으면 저장하고 심사 사건들을 만든다. 심사 승인 결과에 어떤 자료·빌드를 판단했는지 넣는 근거가 된다. Studio 자료 전체의 복제본은 아니며 이벤트에 담긴 필드만 보관한다. 예를 들어 상점 자료의 전체 스크린샷·정책 URL 목록은 이 모델 필드에 없다.

<a id="review-case"></a>
## ReviewCase — 한 제출물의 한 유형 심사

저장: `review_case`. `(submissionId, reviewType)`별 한 건. [모델](../../../apps/review/src/main/java/com/stove/review/core/domain/ReviewCase.java)

| 속성 | 뜻과 사용처 |
|---|---|
| `id`, `submissionId` (`Long`) | 사건 ID·제출물 |
| `reviewType` (`ReviewType`) | `RATING` 등급, `STORE_PAGE` 소개, `BUILD_QA` 빌드 품질, `LEGAL` 법무, `SDK_COMPLIANCE` SDK 준수, `COMMERCIAL` 상업 조건 |
| `status` (`ReviewCaseStatus`) | 요청·외부 접수·승인·수정 요청·차단·취소·만료 |
| `assignedTo` (`String`), `assignedAt`, `dueAt` (`Instant`) | 담당자·배정 시각·처리 기한 |
| `checklistJson`, `internalMemo` (`String`) | 체크리스트와 내부 검토 메모 |
| `decidedBy` (`String`), `decidedAt` (`Instant`) | 판단한 주체와 시각 |
| `reasonCode`, `externalFeedback`, `evidenceUrl` (`String`) | 사유 분류·창작자에게 보일 피드백·판단 증빙 |
| `reviewRound` (`int`), `appealReason` (`String`) | 재검토 회차와 이의 제기 사유 |
| `ratingPath`, `recommendedRatingCode` (`String`) | 등급 사건의 심사 경로·추천 등급 |
| `ratingCode`, `certificationNumber`, `issuer`, `country` (`String`), `issuedAt` (`Instant`) | 승인된 등급·인증번호·발급 주체·국가·발급 시각 |
| `externalApplicationNumber`, `externalEvidenceUrl` (`String`), `externalSubmittedAt` (`Instant`) | 외부 등급 접수번호·증빙·접수 시각 |
| `entityVersion` (`long`) | 동시 배정·메모·결정 변경의 충돌 감지 |

**만들고 쓰는 때:** 제출 이벤트를 받으면 여섯 유형의 사건을 만든다. 담당자가 배정·검토·판단하며 승인 또는 수정 요청 결과를 Studio에 전달한다. 한 사건의 승인으로 전체 출시가 승인되는 것은 아니다.

| 행동 | 현재 상태의 변화·조건 |
|---|---|
| 일반 검토 | `REQUESTED`에서 `APPROVED`, `CHANGES_REQUESTED`, `BLOCKED`, `CANCELLED`, `EXPIRED` |
| 외부 등급 접수 | GRAC 등급 사건에 `EXTERNAL_SUBMITTED` 상태와 접수 증빙 기록 |
| 자체등급 승인 | 코드 정책의 추천 ALL/12/15와 국가를 검사하고 인증 결과 기록 |
| 외부등급 승인 | 접수 이력·인증번호·발급 주체·시각·국가 등의 증빙 검사 |
| 재검토 | `CHANGES_REQUESTED/BLOCKED/EXPIRED`에서 사유를 받아 `REQUESTED`로 복귀, 회차 증가 |

`checklistJson`과 담당자 필드는 존재하지만 현재 승인에 체크리스트 완료·담당자 일치가 필수로 검사되지는 않는다. 차단·취소·만료는 Review 안에서 기록되며 Studio 관문에 동일 상태가 반영되지 않는다. [Q4](../open-questions.md#q4)

내부 입력 값 `ReviewCase.RatingDecision`은 `ratingCode`, `certificationNumber`, `issuer`, `issuedAt`, `country`다. 사유는 `ReviewReasonCode`의 자료 누락·이미지 품질·빌드 실패·정책 위반·법무 문서·SDK 요건·상업 조건·외부 의존·창작자 요청·기한 초과·기타 등의 코드로 분류한다.

근거: [SubmissionReviewService](../../../apps/review/src/main/java/com/stove/review/core/service/SubmissionReviewService.java), [ReviewCaseTest](../../../apps/review/src/test/java/com/stove/review/core/domain/ReviewCaseTest.java), [SubmissionReviewServiceTest](../../../apps/review/src/test/java/com/stove/review/core/service/SubmissionReviewServiceTest.java).

**업무 규칙 제안:** 심사자는 고정된 자료를 검토하고 유형에 맞는 근거로 결정한다. 수정 요청은 외부 피드백, 내부 검토 사항은 내부 메모로 기록한다. 같은 자료에 대한 재검토는 이력과 회차를 보존한다.

**사례:** BUILD_QA를 승인해도 LEGAL이 남아 있으면 전체 출시는 대기한다. 자체등급 경로에서 18세 승인을 요청하거나, 외부 접수 증빙 없이 GRAC 승인을 요청하면 거절된다. 현재 승인 시 체크리스트 완료나 담당자 일치 검사는 없으므로 필수 승인 조건으로 단정하지 않는다.

**합의 상태:** 재검토 필요. 승인 조건과 운영 상태 전달은 [Q4](../open-questions.md#q4), 기존 경로와 명칭은 [Q2](../open-questions.md#q2).

**검증 근거:** [ReviewCase](../../../apps/review/src/main/java/com/stove/review/core/domain/ReviewCase.java), [SubmissionReviewService](../../../apps/review/src/main/java/com/stove/review/core/service/SubmissionReviewService.java). [ReviewCaseTest](../../../apps/review/src/test/java/com/stove/review/core/domain/ReviewCaseTest.java)의 `externalRatingRequiresCompleteEvidence`, `appealReopensBlockedReview`; [SubmissionReviewServiceTest](../../../apps/review/src/test/java/com/stove/review/core/service/SubmissionReviewServiceTest.java)의 `rejectsRatingDifferentFromPolicyDecision`.

## ReviewDecisionHistory — 사건 처리 이력

저장: `review_decision_history`. [모델](../../../apps/review/src/main/java/com/stove/review/core/domain/ReviewDecisionHistory.java)

| 속성 | 뜻과 사용처 |
|---|---|
| `id`, `reviewCaseId` (`Long`) | 이력 ID·대상 사건 |
| `action`, `actor` (`String`) | 생성·배정·승인·재검토 등 행동과 주체 |
| `fromStatus`, `toStatus` (`String`) | 행동 전·후 상태 |
| `details` (`String`) | 사유 등 추가 기록 |

**만들고 쓰는 때:** 사건을 생성·배정·수정·판단할 때 이력을 추가한다. 현재 상태는 ReviewCase에서 읽고, 어떻게 그 상태에 이르렀는지는 이 이력에서 읽는다. 시각은 상속한 `createdAt`·`updatedAt`으로 보관한다. 서비스 전체의 일반 `AuditLog`와 달리 사건별 이력 조회를 위한 구조다.

## ReviewRequest — 기존 프로젝트 단위 심사

저장: `review_request`. [모델](../../../apps/review/src/main/java/com/stove/review/core/domain/ReviewRequest.java)

| 속성 | 뜻과 사용처 |
|---|---|
| `id`, `gameId` (`Long`), `productCode` (`String`) | 심사 요청·프로젝트·상품 코드 |
| `title` (`String`), `sellerId` (`Long`) | 신청 제목·판매자 |
| `price` (`long`), `currency` (`String`), `selfRated` (`boolean`) | 신청 가격·통화·자체등급 여부 |
| `status` (`ReviewStatus`) | `REQUESTED`, `IN_REVIEW`, `APPROVED`, `REJECTED` |
| `boardTicketId`, `ratingCode` (`String`) | 외부 접수 참조·승인 등급 |
| `rejectReasonCode`, `rejectReason` (`String`), `closedAt` (`Instant`) | 반려 분류·설명·종료 시각 |

**만들고 쓰는 때:** 기존 `GameRegistered` 이벤트 경로에서 생성되고 승인·반려 이벤트를 Studio·Catalog가 받는다. 제목·가격을 갱신해 재신청할 수 있다. 현재 Submission+ReviewCase 경로의 여섯 사건을 이 모델 한 건으로 대체해서 설명하면 안 된다. 기존 승인으로 Catalog 상품이 생겨도 LIVE 릴리스 없이는 판매를 시작할 수 없다. [ReviewService](../../../apps/review/src/main/java/com/stove/review/core/service/ReviewService.java), [Q2](../open-questions.md#q2).

[RatingBoardSubmission](../../../apps/review/src/main/java/com/stove/review/core/domain/RatingBoardSubmission.java)은 외부 등급 접수에 넘기는 값 묶음이다. `submissionId`, `productCode`, `title`, `sellerId`, `buildId`, `productVersion`, `policyVersion`, `country`, `targetRatingCode`, `questionnaire`를 담는다. 실제 외부 기관 연동은 현재 대역 구현이라는 점을 함께 읽는다.

Review의 일반 `AuditLog` 속성은 [공통 감사 기록](shared.md#audit-log)을 참고한다.
