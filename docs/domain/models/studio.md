# 출시 자료와 심사·출시 요청 모델

[전체 안내](../model-guide.md) · [실행·검증](../../studio-guide.md) · [계획](../../studio-simplification-plan.md)

Studio는 창작자의 출시용 상세정보·빌드를 관리하고 심사·출시 요청과 재요청을 발행한다.
심사 판정, 실제 출시, 공개 상품·공개본 관리의 책임은 외부 업무에 있다.
Studio는 외부 결과를 해당 요청의 상태·피드백으로 기록한다.

~~~mermaid
flowchart LR
 A[Auth JWT sub·CREATOR] --> G[Gateway]
 G --> P[Project 상세정보·빌드]
 P --> R[심사 요청 스냅샷 발행]
 R --> E[외부 심사 결과 수신]
 E --> C[현재 버전의 출시 요청 조건]
 C --> L[출시 요청 발행]
 L --> F[외부 출시 결과 수신·요청 이력]
~~~

<a id="workspace"></a>
## 소유자

완료된 기준 모듈은 Auth·Gateway다. Project 소유자는 Auth JWT sub로 직접 식별한다.
Workspace와 sellerId 매핑은 제거했다. 미완료 모듈은 설계·검증 기준에 포함하지 않는다.

<a id="game-project"></a>
## Project

저장: studio_project. [Project](../../../apps/studio/src/main/java/com/stove/studio/core/domain/project/Project.java)

| 값 | 의미 |
|---|---|
| id, productCode, ownerSubject | Project와 Auth 소유자 식별 |
| title, shortDescription, thumbnailUrl, videoUrl, content, price | 관리하는 출시 상세정보 |
| currentBuildId, contentVersion | 대표 전체 빌드와 상세정보 버전 |
| currentReviewRequestId, currentReleaseRequestId | 가장 최근에 발행한 요청 참조 |
| entityVersion | DB 동시 수정 충돌 감지 |

Project의 승인·출시 상태와 공개본 참조는 없다. 상세정보 수정에는 현재 contentVersion을 보내며 오래된 편집은 409다.
처리 중인 요청이 있어도 상세정보를 수정할 수 있다. 변경된 버전은 새 심사 결과가 필요하다.

<a id="game-build"></a>
## Build

저장: studio_build. [Build](../../../apps/studio/src/main/java/com/stove/studio/core/domain/build/Build.java)

Project에 등록한 전체 ZIP의 버전·파일명·경로·크기·SHA-256을 보관한다.
임시 디스크에서 형식·기본 경로·크기를 확인한 뒤 S3/MinIO에 저장하고 대표 빌드를 바꾼다.
같은 Project의 버전은 중복 등록할 수 없다. 기존 요청에 첨부한 파일은 덮어쓰지 않는다.
플랫폼별 묶음·델타·manifest·실행 파일 규칙·업로드 세션은 없다. 기본 상한은 1GiB다.

<a id="submission"></a>
## ProjectReviewRequest

저장: studio_review_request. [심사 요청](../../../apps/studio/src/main/java/com/stove/studio/core/domain/review/ProjectReviewRequest.java)

Project·contentVersion·buildId와 출시 자료의 불변 snapshot을 보관한다.
REQUESTED는 발행한 요청이며 APPROVED·REJECTED는 외부 심사 업무가 보고한 결과다.
피드백, 외부 처리자와 결과 수신 시각을 기록한다. Studio가 판정하거나 승인 기준을 평가하지 않는다.
현재 상세정보 버전의 승인 결과만 출시 요청 조건으로 사용할 수 있다.
반려 후 재요청은 새 요청 ID를 만들며 과거 요청 이력을 보존한다.

<a id="release"></a>
## ProjectReleaseRequest

저장: studio_release_request. [출시 요청](../../../apps/studio/src/main/java/com/stove/studio/core/domain/release/ProjectReleaseRequest.java)

Project·버전·승인 결과를 받은 심사 요청과 요청 이벤트 ID를 연결한다.
REQUESTED·COMPLETED·REJECTED는 요청과 외부 처리 결과를 표시한다.
거절 사유와 결과 수신 시각을 기록한다. 실제 출시·상품 등록·진열·공개본 전환은 수행하지 않는다.
거절 후 재요청은 새 ID를 사용하며 이전 결과가 새 요청을 덮어쓰지 않는다.

## 조회와 검증

현재 상세정보 버전의 요청은 Project 조회의 currentReviewRequest·currentReleaseRequest로 표시한다.
releaseEligible은 현재 버전에 해당하는 외부 승인 결과의 존재 여부다. 과거 요청은 이력 API에서 조회한다.
썸네일은 PNG/JPEG 한 개로 기본 5MiB·8192픽셀, 영상은 외부 URL이다.

V25에서 기존 저장 구조를 요청 모델로 전환하며 SQL 이력과 파일·스냅샷은 보존한다.
검증: [ProjectTest](../../../apps/studio/src/test/java/com/stove/studio/core/domain/project/ProjectTest.java),
[ProjectRequestIntegrationTest](../../../apps/studio/src/integrationTest/java/com/stove/studio/core/service/ProjectRequestIntegrationTest.java),
[Auth·Gateway·Studio E2E](../../../e2e/src/test/java/com/stove/e2e/StudioCreatorFlowTest.java).

## 제거된 구 모델

<a id="store-page-revision"></a>
<a id="pricing-revision"></a>
<a id="rating-revision"></a>
<a id="submission-build"></a>
<a id="submission-gate"></a>
<a id="upload-session"></a>
<a id="bundle-component"></a>

다중 자료 판본·심사 관문·채널·예약·업로드 세션·번들은 사용하지 않는다.
현재 업무 모델은 Project, Build, ProjectReviewRequest, ProjectReleaseRequest다.
