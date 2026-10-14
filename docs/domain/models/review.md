# Project 스냅샷 심사 모델

[전체 안내](../model-guide.md) · [실행·검증](../../review-guide.md) · [개선 계획·완료 기록](../../review-simplification-plan.md)

Review는 Studio가 제출한 상세정보·전체 빌드 스냅샷과 그에 대한 최초 최종 판정의 원본을 관리한다.
2026-10-14 재작성을 완료했다. 심사 결과를 받은 Studio는 자신의 요청 기록과 출시 요청 조건만 갱신한다.

## ProjectReview

저장: `project_review`. [모델](../../../apps/review/src/main/java/com/stove/review/core/domain/ProjectReview.java)

| 값 | 의미 |
|---|---|
| reviewRequestId | Studio 요청 ID와 같은 PK. 새 요청마다 새 심사 |
| requestEventId | 최초 접수 이벤트 ID |
| projectId·productCode·ownerSubject·contentVersion | 제출 스냅샷에서 고정한 대상·창작자·버전 |
| snapshot | ProjectSnapshot 전체의 불변 JSON |
| status | REQUESTED·APPROVED·REJECTED |
| feedback | 반려 사유, 필수·최대 2000자 |
| actor·decidedAt | 최초 최종 판정자의 Auth sub와 판정 시각 |
| requestedAt·createdAt·updatedAt | 원본 요청 발생·수신·저장 시각 |
| entityVersion | JPA 저장·동시 변경 검출용 버전. contentVersion과 별개 |

REQUESTED에서 승인 또는 반려로 한 번 전이한다.
같은 판정·사유의 재시도는 최초 결과를 반환하며, 반대 판정이나 사유 변경은 409다.
행 잠금 아래 판정과 결과 Outbox를 함께 저장하므로 동시 승인·반려가 두 결과를 만들지 않는다.

Studio의 새 요청은 Project·버전·빌드가 같아도 별도 심사로 접수한다.
과거 반려 건을 다시 열거나 과거 승인을 새 요청에 연결하지 않는다.
심사 중 창작자가 작업본을 바꾸더라도 원래 스냅샷을 판단한다.
승인이 현재 버전의 출시 요청 조건인지 판단하는 책임은 Studio에 있다.

## 스냅샷과 빌드 열람

수신하는 ProjectSnapshot은 제목·소개·썸네일·영상·내용·가격과
빌드 ID·버전·크기·SHA-256·저장 경로를 포함한다.
현재 Studio를 조회해 값을 덮어쓰지 않는다.

API 상세 응답은 스냅샷에서 내부 저장 경로를 제외한다.
심사자·운영자는 해당 요청 ID로 읽기 전용 S3/MinIO 다운로드 URL을 발급받는다.
객체 존재·크기를 확인하고 유효기간·크기·checksum을 함께 반환한다.
URL은 기본 5분이며 1초~15분으로 설정할 수 있다. 파일 업로드·삭제·버킷 생성 API는 없다.

## 결과와 이력

ProjectReviewRequested를 접수하고 ProjectReviewApproved 또는 ProjectReviewRejected를 발행한다.
결과의 요청 ID·Project·버전·상품 코드는 저장된 제출 값이며 actor는 검증된 JWT sub다.
Creator는 자기 요청 결과를 Studio에서 조회한다.
Reviewer·Admin은 Review에서 Project 필터로 과거 요청과 최종 판정을 조회한다.
별도 판정 수정·담당자 배정·이의 제기·유형별 심사 모델은 없다.

## 전환과 검증

V1~V9의 SQL 이력은 유지하고 V10에서 새 테이블을 추가했다.
구 심사 자료는 보존하되 새 업무 모델·접수·판정에 사용하지 않는다.

[도메인 규칙](../../../apps/review/src/test/java/com/stove/review/core/domain/ProjectReviewTest.java),
[권한·응답 계약](../../../apps/review/src/test/java/com/stove/review/api/controller/ReviewAccessTest.java),
[실제 DB·Kafka 판정 흐름](../../../apps/review/src/integrationTest/java/com/stove/review/core/service/ProjectReviewIntegrationTest.java),
[빌드 열람](../../../apps/review/src/integrationTest/java/com/stove/review/infrastructure/storage/ReviewBuildDownloadIntegrationTest.java),
[네 모듈 인수 검증](../../../e2e/src/test/java/com/stove/e2e/ReviewCreatorFlowTest.java)을 기준으로 한다.

## 보존된 구 자료

<a id="review-case"></a>
<a id="submission-snapshot"></a>
<a id="review-request"></a>
<a id="review-decision-history"></a>
<a id="audit-log"></a>

구 ReviewRequest·SubmissionSnapshot·ReviewCase·감사·판정 이력 테이블은 역사 자료다.
현재 실행 코드와 5개 업무 API는 ProjectReview만 사용한다.
