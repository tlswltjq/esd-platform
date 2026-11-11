# 요구사항: Studio (출시 상세정보·빌드, 심사/출시 요청과 철회 명령)

> 상태: 작성 중(결정 150·178). 모듈을 넘는 계약은 [플랫폼 스펙](../platform/requirements.md)이 정본이고 이 문서는 링크한다.

## 개요

Studio는 창작자(CREATOR)가 게임을 출시하기 위한 작업 도구다. 상세정보 작업본·전체 빌드·썸네일을 관리하고, 작업본을 불변 스냅샷으로 고정해 심사 요청·출시 요청을 내며, 진행 중인 요청의 철회 명령을 내구 접수한다.
심사 판정의 정본은 Review, 공개 확정의 정본은 Catalog다([플랫폼 design "정본 소유"](../platform/design.md#정본-소유)). Studio는 자기 요청에 대해 그 모듈이 낸 결과 사실을 받아 요청·명령의 상태로 기록만 한다.
소유자는 Auth JWT의 `sub`로 직접 식별한다(판매자 매핑 없음). 포트 8085, 저장소 MySQL `stove_studio`, 객체 저장소 버킷 하나에 빌드·썸네일을 쓴다.

Studio가 끼는 모듈 간 흐름은 [플랫폼 흐름 1 출시 → 공개](../platform/design.md#흐름-1-출시--공개-studio--review--studio--catalog--store--download)의 S1~S7·S6b이고, 요구사항은 [P3.2](../platform/requirements.md#p32-심사-요청철회-studio--review)·[P3.3](../platform/requirements.md#p33-심사-판정-결과-review--studio)·[P3.4](../platform/requirements.md#p34-출시-요청철회-studio--catalog)·[P3.5-5](../platform/requirements.md#p35-공개-확정-catalog)·[P3.6](../platform/requirements.md#p36-출시-결과-기록-catalog--studio)·[P3.9](../platform/requirements.md#p39-출시-흐름의-모듈-간-정합)다. 이 문서는 그 계약을 Studio가 어떻게 지키는지(HTTP 명령 → Project 잠금 → 상태 전이 → DB 제약 → Outbox, 리스너 → `*Mapper.from` → 서비스 → 상태 전이)를 적는다.

## 용어

- **CREATOR**: 창작자 역할. `/api/v1/studio/**`(공개 썸네일 제외)의 모든 명령·조회에 필요하다.
- **ownerSubject**: Project 소유자. Auth JWT의 `sub`이며 생성 뒤 바뀌지 않는다.
- **작업본**: Project 행이 가진 현재 상세정보(제목·짧은 소개·썸네일·영상 URL·내용·가격·장르)와 대표 빌드.
- **contentVersion**: 작업본 버전. 1부터 시작하고 상점 정보나 대표 빌드가 실제로 바뀔 때만 1 증가한다. Catalog의 신구 판정 기준이다([P3.5-2](../platform/requirements.md#p35-공개-확정-catalog)).
- **스냅샷(`ProjectSnapshot`)**: 심사 요청 시점의 작업본·빌드를 고정한 불변 값. 심사 요청 행에 저장하고, 출시 요청은 승인된 심사 요청의 스냅샷을 그대로 싣는다.
- **심사 요청 / 출시 요청**: Studio가 낸 요청 행(`studio_review_request`·`studio_release_request`). 요청 ID는 Studio가 발급한 행 ID이며 Review·Catalog의 `reviewRequestId`·`releaseRequestId`다.
- **명령(command)**: 창작자가 HTTP로 보낸 지시의 내구 영수증(`studio_request_command`). 제출 명령(심사·출시 요청)과 철회 명령이 있다. 명령 식별자는 창작자가 정하는 `requestId`(요청 본문)이며 Studio 안에서는 `request_key`다.
- **활성 요청(active request)**: 상태가 `REQUESTED`인 요청. 프로젝트 전체에서 심사·출시 각각 1건 이하다(결정 63·76).
- **현재 버전 요청**: Project가 가리키는 최근 요청(`current_review_request_id`·`current_release_request_id`) 중 `contentVersion`이 작업본과 같은 것.
- **releaseEligible**: 현재 버전 심사 요청이 `APPROVED`이면 참. 출시 요청의 필요 조건이며 충분 조건이 아니다.
- **결과 사실**: Review·Catalog가 Studio 요청에 대해 낸 이벤트(판정·출시 결과·철회 결과·운영자 종결). 이벤트 목록은 [플랫폼 design "이벤트 목록"](../platform/design.md#이벤트-목록)이 정본이다.
- **정방향 대기**: Studio가 낸 요청·철회 명령 가운데 결과 사실을 아직 받지 못한 자기 기록([결정 158](../../../docs/decisions.md#forward-gap-counting)).

## 요구사항

### R1. 상세정보·빌드·썸네일 관리
**사용자 스토리:** 창작자로서, 출시용 상세정보와 전체 빌드를 올리고 싶다. 그래야 심사·출시를 요청할 수 있다.

#### 수용 기준
1. WHEN CREATOR가 `POST /api/v1/studio/projects`로 `productCode`·`title`을 보내면 THEN Studio SHALL JWT `sub`를 `ownerSubject`로, `contentVersion=1`로 Project를 만들고 201로 응답한다. IF `productCode`가 이미 있으면 THEN Studio SHALL 409로 거절한다(`uk_studio_project_code`).
2. THE 작업본 SHALL 제목·짧은 소개·썸네일 URL·선택적 HTTP/HTTPS 영상 URL·내용·KRW 정수 가격(0 이상)·장르 집합을 보관한다.
3. WHEN 상점 정보나 대표 빌드가 실제로 바뀌면 THEN Studio SHALL `contentVersion`을 1 증가시킨다. 값이 같은 수정(장르 순서만 다른 경우 포함)은 버전을 바꾸지 않는다.
4. THE 전체 빌드 SHALL ZIP 한 개이며 크기 상한은 설정 `stove.upload.max-artifact-bytes`(기본 1GiB), 썸네일은 PNG/JPEG에 `stove.asset.max-thumbnail-bytes`(기본 5MiB)·8192픽셀을 상한으로 둔다.
5. WHEN 파일을 업로드하면 THEN Studio SHALL 임시 디스크에서 형식 검사·SHA-256 계산을 마친 뒤 객체 저장소에 고유 경로로 저장하고, 짧은 DB 트랜잭션에서 Project 잠금·소유권·기대 `contentVersion`을 다시 확인한 뒤 빌드 행과 대표 빌드를 기록한다.
6. IF 객체 저장 뒤 DB 트랜잭션이 실패하면 THEN Studio SHALL 새로 쓴 객체를 지우고, 기존 빌드·썸네일 객체를 덮어쓰거나 지우지 않는다.
7. IF 상세정보 수정·빌드 등록의 기대 `contentVersion`이 현재와 다르면 THEN Studio SHALL 409로 거절한다. IF 가격이 음수이거나 정수가 아니면 THEN Studio SHALL 400으로 거절한다.
8. (신규) THE 빌드 버전 문자열 SHALL Project 안에서 유일하다. IF 같은 버전을 다시 등록하면 THEN Studio SHALL 409로 거절하고 새 객체를 지운다.

### R2. 장르 선택
**사용자 스토리:** 창작자로서, 플랫폼이 정한 장르로 상품을 분류하고 싶다. 그래야 탐색과 입력이 일관된다.

#### 수용 기준
1. WHERE 작업본 저장 맥락에서 THE Studio SHALL 장르를 0~3개 허용한다(결정 69·70).
2. WHEN 새 심사를 제출하면 THEN Studio SHALL 현재 선택 가능한 장르 코드 1~3개를 요구한다(결정 69·70).
3. IF 입력 장르가 중복·알 수 없는 코드·상한 초과이면 THEN Studio SHALL 400으로 거절한다.
4. WHERE 장르만 수정하는 맥락에서 THE Studio SHALL 상점 정보 변경과 같이 `contentVersion`을 올려, 새 버전 심사·출시를 거치게 한다.
5. WHEN 알려진 장르가 나중에 선택 중지돼도 THEN Studio SHALL 이미 제출한 스냅샷의 읽기·출시를 유지하고 새 심사 제출만 선택 가능한 장르로 제한한다(결정 73).
6. WHERE CREATOR 장르 기준 조회 맥락에서 THE Studio SHALL `GET /api/v1/studio/genres`로 코드·표시명·선택 가능 여부를 제공한다. 기준 목록은 코드로 정의한다(결정 71·72).

### R3. 심사 요청·재요청
**사용자 스토리:** 창작자로서, 현재 상세정보·빌드로 심사를 요청하고 싶다. 그래야 출시 조건을 확인할 수 있다.

#### 수용 기준
1. WHEN `POST /api/v1/studio/projects/{id}/review-requests`가 `requestId`·`contentVersion`으로 오면 THEN Studio SHALL Project 잠금 안에서 작업본을 불변 스냅샷으로 고정하고, 심사 요청(`REQUESTED`)·제출 명령 영수증(`ACCEPTED`)·`ProjectReviewRequested` Outbox를 한 트랜잭션에 저장한 뒤 202로 접수한다([P3.2-1](../platform/requirements.md#p32-심사-요청철회-studio--review)).
2. THE 심사 제출 본문 SHALL `requestId`(영숫자로 시작, `[A-Za-z0-9._-]` 64자 이하)와 `contentVersion`(1 이상)을 필수로 요구한다.
3. WHEN 같은 `requestId`가 재전송되면 THEN Studio SHALL 최초 영수증이 가리키는 요청을 반환하고 현재 작업본을 새로 제출하지 않는다.
4. IF 같은 `requestId`에 다른 행위자·`contentVersion`이 오면 THEN Studio SHALL 409로 거절한다.
5. WHEN 반려(`REJECTED`)·철회(`WITHDRAWN`)된 버전을 다시 심사받으려면 THEN Studio SHALL 새 `requestId`로 새 심사 요청·새 `ProjectReviewRequested`를 내고 이전 이력을 유지한다(결정 59).
6. THE Studio SHALL 심사 요청을 `ProjectReviewRequested`(`stove.studio.v1`, 키 `productCode`)로만 알린다([design "이벤트 목록(리터럴)"](../platform/design.md#eventtype에-없는-현행-이벤트-문자열-리터럴-10개-t26)).
7. (신규) IF 작업본이 준비되지 않았으면(제목·짧은 소개·내용·썸네일·대표 빌드 중 하나라도 없음, 또는 R2.2 위반) THEN Studio SHALL 409로 거절한다.
8. (신규) IF 대표 빌드 객체의 저장소 크기가 빌드 행의 `fileSize`와 다르거나 객체가 없으면 THEN Studio SHALL 409로 거절하고 요청을 만들지 않는다([P3.4-1](../platform/requirements.md#p34-출시-요청철회-studio--catalog)의 HEAD 확인과 같은 규칙). HEAD 호출과 DB 트랜잭션의 관계는 design "트랜잭션과 잠금"과 tasks ST-T8.
9. (신규) WHEN 현재 버전 심사 요청이 이미 `APPROVED`인데 새 `requestId`로 심사를 요청하면 THEN Studio SHALL 새 심사를 만들지 않고 그 승인된 요청을 가리키는 영수증을 만들어 202로 반환한다.

### R4. 출시 요청·재요청
**사용자 스토리:** 창작자로서, 승인된 상세정보를 출시 요청하고 싶다. 그래야 공개로 이어진다.

#### 수용 기준
1. WHEN `POST /api/v1/studio/projects/{id}/release-requests`가 `requestId`·`contentVersion`으로 오면 THEN Studio SHALL Project 잠금 안에서 [P3.4-1](../platform/requirements.md#p34-출시-요청철회-studio--catalog)의 세 조건을 확인하고, 승인된 심사 요청의 스냅샷 그대로 출시 요청(`REQUESTED`)·제출 명령 영수증·`ProjectReleaseRequested` Outbox를 한 트랜잭션에 저장한 뒤 202로 접수한다.
2. IF 현재 `contentVersion`의 심사 요청이 `APPROVED`가 아니면 THEN Studio SHALL 409로 거절한다.
3. THE 다른 버전의 승인 SHALL 현재 버전 출시 요청의 근거가 되지 않는다.
4. WHEN 같은 `requestId`가 재전송되면 THEN Studio SHALL 최초 영수증이 가리키는 요청을 반환하고 새 이벤트를 내지 않는다.
5. THE Studio SHALL 출시 요청을 `ProjectReleaseRequested`(`stove.studio.v1`, 키 `productCode`)로만 알린다.
6. THE 출시 요청 202 SHALL 발행 접수이며 공개 완료가 아니다. 출시 명령은 승인본의 자동 공개를 요청하며 유예 시간이 없다(결정 62). 승인만으로는 공개하지 않는다.
7. THE `releaseEligible` SHALL 현재 버전 승인 근거일 뿐이며 활성 요청·철회 명령·파일 상태의 서버 검사를 대신하지 않는다.
8. WHEN 거절(`REJECTED`)·철회(`WITHDRAWN`)·운영자 종결(`CLOSED`) 뒤 같은 승인본을 다시 출시하려면 THEN Studio SHALL 새 `requestId`를 요구하고 새 출시 요청을 만든다(결정 58·168). 과거 `requestId` 재전송에는 과거 요청을 반환한다.
9. (신규) WHEN 현재 버전 출시 요청이 이미 `COMPLETED`인데 새 `requestId`로 출시를 요청하면 THEN Studio SHALL 새 출시 요청을 만들지 않고 그 완료된 요청을 가리키는 영수증을 202로 반환한다.
10. THE 공개본이 Review가 승인한 스냅샷이라는 조건(RI-2)의 공개 직전 가드 SHALL Catalog에 있다([결정 167](../../../docs/decisions.md#catalog-verifies-approval), [P3.4-2](../platform/requirements.md#p34-출시-요청철회-studio--catalog)). Studio의 R4.2·R4.3은 요청하는 쪽의 검사이며 RI-2를 혼자 지키는 곳으로 삼지 않는다.

### R5. 프로젝트 전체 단일 심사·단일 출시
**사용자 스토리:** 창작자로서, 진행 중인 요청이 명확하길 바란다. 그래야 중복 제출을 피한다.

#### 수용 기준
1. THE Studio SHALL 프로젝트의 모든 버전을 통틀어 활성 심사 요청을 1건 이하로 둔다(결정 76, `uk_studio_active_review`).
2. THE Studio SHALL 프로젝트의 모든 버전을 통틀어 활성 출시 요청을 1건 이하로 둔다(결정 63, `uk_studio_active_release`).
3. WHILE 활성 요청에 `PENDING` 철회 명령이 있으면 THE Studio SHALL 그 요청을 활성으로 유지하고 새 요청 자리를 열지 않는다. 자리는 대상 요청이 최종 상태가 될 때만 풀린다(결정 54).
4. WHERE 활성 요청 조회 맥락에서 THE Studio SHALL `activeReviewRequest`·`activeReleaseRequest`를 작업본 버전과 독립적으로 제공한다.
5. WHILE 이전 버전의 출시가 활성이면 THE Studio SHALL 다음 버전의 편집·심사를 허용한다.
6. WHEN 같은 Project에 요청이 동시에 들어오면 THEN Studio SHALL Project 행 잠금으로 직렬화하고 DB 유일 키로 활성 요청 1건을 보장한다.
7. IF 다른 버전의 요청이 활성이거나 활성 요청에 `PENDING` 철회 명령이 있으면 THEN Studio SHALL 새 `requestId`의 요청을 409로 거절한다.
8. (신규) WHEN 현재 버전의 요청이 활성이고 `PENDING` 철회 명령이 없는데 새 `requestId`로 같은 종류의 요청이 오면 THEN Studio SHALL 새 요청을 만들지 않고 활성 요청을 가리키는 영수증을 만들어 202로 반환한다.

### R6. 심사·출시 철회 명령(내구 접수)
**사용자 스토리:** 창작자로서, 잘못 보낸 요청을 명시적으로 철회하고 싶다. 그래야 다시 올바르게 제출할 수 있다.

#### 수용 기준
1. WHEN `POST …/review-requests/{targetId}/withdrawals` 또는 `POST …/release-requests/{targetId}/withdrawals`가 `requestId`·선택 `reason`으로 오면 THEN Studio SHALL Project 잠금 안에서 철회 명령(`PENDING`)과 철회 요청 이벤트 Outbox를 한 트랜잭션에 저장하고 202·`Cache-Control: no-store`로 영수증을 응답한다([P3.2-4](../platform/requirements.md#p32-심사-요청철회-studio--review), 결정 54).
2. THE 철회 영수증 SHALL `PENDING`에서 `WITHDRAWN` 또는 `DENIED`로만 확정된다. `WITHDRAWN`이면 철회를 확정한 모듈의 확정 시각(값으로만 저장)과 철회 행위자를, `DENIED`이면 철회를 막은 대상의 최종 상태(`targetStatus`)와 확정 시각을 보존한다. 대상 판정의 행위자·사유는 영수증에 다시 적지 않고 대상 요청의 결과로 보여 준다(결정 176).
3. THE 철회 접수 SHALL 최종 성공을 보장하지 않는다(접수와 완료를 구분, 결정 54). Studio는 시간 경과로 결과를 정하지 않는다.
4. WHEN 심사 철회가 `WITHDRAWN`으로 확정되면 THEN Studio SHALL 같은 자료의 새 심사를 새 `requestId`로 허용한다(결정 59).
5. WHEN 출시 철회가 `WITHDRAWN`으로 확정되면 THEN Studio SHALL 기존 승인본의 새 출시 요청을 새 `requestId`로 허용한다(결정 58).
6. WHERE 소유권 맥락에서 THE Studio SHALL CREATOR `sub` = Project 소유자, 대상 요청의 Project = 경로 Project를 확인하고, 영수증 조회를 소유자와 경로가 지정한 대상에 제한한다(다른 대상의 영수증은 404).
7. THE Studio SHALL `ProjectReviewWithdrawalRequested`·`ProjectReleaseWithdrawalRequested`(`stove.studio.v1`)를 발행한다. 본문의 `withdrawalId`는 철회 명령 ID다.
8. THE `reason` SHALL 선택 입력이며 앞뒤 공백을 지운 뒤 최대 2000자, 빈 문자열은 없음으로 본다(결정 61). IF 같은 종류·같은 `requestId`로 대상이나 사유가 다르면 THEN Studio SHALL 409로 거절하고, 같은 명령의 재전송에는 기존 영수증을 반환한다.
9. IF 대상 요청이 이미 최종 상태이거나, 같은 대상에 `PENDING` 철회 명령이 이미 있으면 THEN Studio SHALL 새 철회 명령을 409로 거절하고 이벤트를 내지 않는다.
10. THE 철회 SHALL 공개된 상품을 되돌리지 않고 판매·노출·다운로드 설정과 제출 스냅샷·빌드를 바꾸거나 지우지 않는다([P3.4-7](../platform/requirements.md#p34-출시-요청철회-studio--catalog)).
11. THE 출시 철회 SHALL 공개 확정 전에만 이길 수 있는 시도이며, 사용자가 철회할 시간을 보장하지 않는다(결정 57·62).

### R7. 결과 사실 수신과 요청 상태
**사용자 스토리:** 창작자로서, 심사·출시 결과를 요청 이력에서 확인하고 싶다. 그래야 다음 행동을 정할 수 있다.

#### 수용 기준
1. WHEN 결과 사실이 소비 그룹 `studio-project`에 오면 THEN Studio SHALL 대상 요청의 ID·`projectId`·`contentVersion`·`productCode`(그리고 출시 결과는 `requestEventId` = Studio가 낸 원요청 이벤트 ID)가 모두 맞을 때만 그 요청에 기록한다([P3.3-3](../platform/requirements.md#p33-심사-판정-결과-review--studio)·[P3.6-1](../platform/requirements.md#p36-출시-결과-기록-catalog--studio)).
2. THE 심사 요청 상태 SHALL `ReviewRequestStatus` {`REQUESTED`, `APPROVED`, `REJECTED`, `WITHDRAWN`} 중 하나이고 DB CHECK가 지킨다.
3. THE 출시 요청 상태 SHALL `ReleaseRequestStatus` {`REQUESTED`, `COMPLETED`, `REJECTED`, `WITHDRAWN`, `CLOSED`} 중 하나이고 DB CHECK가 지킨다. `CLOSED`는 ADMIN이 Catalog에서 공개 없이 종결한 출시다(결정 168).
4. IF 최종 상태 요청에 같은 결과가 다시 오면 THEN Studio SHALL 변화 없이 성공한다. IF 다른 결과가 오거나 식별자가 맞지 않으면 THEN Studio SHALL 기존 기록을 덮어쓰지 않고 영구 실패로 처리해 실패 보관·경보로 보낸다([P3.1-6](../platform/requirements.md#p31-흐름-공통-규칙), MC-ST2). 조용히 성공 처리하지 않는다.
5. WHEN 상세정보 수정 뒤 과거 버전 요청의 결과가 도착하면 THEN Studio SHALL 그 과거 요청에만 기록하고 현재 버전의 `releaseEligible`·활성 요청에 영향을 주지 않는다.
6. THE Studio SHALL 다음 결과 사실을 받는다: Review의 `ProjectReviewApproved`·`ProjectReviewRejected`·`ProjectReviewWithdrawn`·`ProjectReviewWithdrawalDenied`(`stove.review.v1`), Catalog의 `ProjectReleaseCompleted`·`ProjectReleaseRejected`·`ProjectReleaseWithdrawn`·`ProjectReleaseWithdrawalDenied`·`ProjectReleaseClosed`(가칭)(`stove.release.v1`). 이행 중에는 옛 `ProjectReviewWithdrawalResolved`·`ProjectReleaseWithdrawalResolved`도 받는다(결정 176, [P2.7-4](../platform/requirements.md#p27-스키마-호환-결정-104)).
7. (신규) WHEN Studio가 `ProjectReleaseClosed`를 받으면 THEN Studio SHALL 대상 출시 요청을 `CLOSED`로 끝내고 활성 출시 자리를 푼다([P3.5-5](../platform/requirements.md#p35-공개-확정-catalog), 결정 168).
8. (신규) THE `studio-project` 리스너 SHALL (토픽, `eventType`) 짝을 대조해 review 토픽의 출시 결과·release 토픽의 심사 결과를 영구 실패로 거절한다([P2.3-8](../platform/requirements.md#p23-소비--inbox와-업무-멱등), MC-ST1).
9. (신규) THE 결과 사실의 행위자·시각 SHALL 기록·표시에만 쓰고, Studio 시계와 선후를 비교해 거절·분기하지 않는다([P2.1-3](../platform/requirements.md#p21-봉투), 원칙 ⑥).

### R8. 목록 페이지 조회
**사용자 스토리:** 창작자로서, 프로젝트·빌드·요청 이력을 페이지로 조회하고 싶다. 그래야 이력이 늘어도 다룰 수 있다.

#### 수용 기준
1. THE 프로젝트·빌드·심사/출시 요청 이력의 GET 목록 SHALL 기본 `page=0`·`size=20`, 최대 100, ID 내림차순이다(결정 64·65).
2. THE 응답 `data` SHALL `items/page/size/totalElements/totalPages` 구조다.
3. IF 음수 페이지·범위 밖 크기·숫자 아닌 인자·정수 offset 초과이면 THEN Studio SHALL 400으로 거절한다. 마지막 이후 페이지는 빈 `items`와 실제 총건수를 반환한다.
4. WHERE 조회 한도 맥락에서 THE Studio SHALL 소유자·프로젝트 조건을 count 조회에도 적용하고 DB에서 조회량을 제한한다.
5. THE 철회 영수증 목록 SHALL 대상 요청별 최대 100건 배열을 ID 내림차순으로 반환한다(기존 제한 조회 계약 유지).
6. WHERE 프로젝트 페이지 조회에서 THE Studio SHALL 현재 버전 요청·활성 요청·장르를 페이지 단위 일괄 조회로 읽고 프로젝트 수에 비례한 추가 조회를 하지 않는다.

### R9. 소유권과 공개 경계
**사용자 스토리:** 창작자로서, 내 자료에만 접근이 허용되길 바란다. 그래야 자료가 안전하다.

#### 수용 기준
1. WHERE `/api/v1/studio/**`(공개 썸네일 제외) 맥락에서 THE Studio SHALL CREATOR 역할과 Project 소유권을 확인한다. 다른 소유자의 Project는 403, 없는 Project는 404다.
2. WHERE `GET /api/v1/studio/assets/{projectId}/{assetId}` 맥락에서 THE Studio SHALL 인증 없이 썸네일 조회를 허용한다.
3. THE Project SHALL `APPROVED`·`PUBLISHED` 같은 공개 상태나 공개본 참조, `/publish` API를 두지 않는다. 심사 판정·공개 확정을 수행하는 HTTP API도 두지 않는다.

### R10. 정방향 대기 계수 (신규)
**사용자 스토리:** 운영자로서, Studio가 낸 요청이 다른 모듈에 반영되지 않은 채 남은 것을 알고 싶다. 그래야 유실을 사람이 찾기 전에 고칠 수 있다.

#### 수용 기준
1. THE Studio SHALL 결과 사실을 받지 못한 자기 기록 — 활성 출시 요청(`REQUESTED`), `PENDING` 철회 명령(종류별) — 의 건수와 가장 오래 기다린 시간(Studio 자기 시계의 생성 시각 기준)을 게이지로 낸다([P3.9-2](../platform/requirements.md#p39-출시-흐름의-모듈-간-정합), [결정 158](../../../docs/decisions.md#forward-gap-counting), design "정방향 응답 쌍").
2. THE 활성 심사 요청(`REQUESTED`) SHALL 같은 방식으로 센다. 이 쌍의 응답 사실은 사람의 판정이라 대기 시간이 전달 누락과 판정 지연을 구별하지 못한다 — 구별 방법은 [결정 필요 M-ST-3].
3. THE 경보 기준 시간 SHALL 측정 뒤 정하고, 그 전에는 게이지만 낸다([결정 159](../../../docs/decisions.md#forward-gap-threshold)).
4. THE 게이지 SHALL Studio 스키마만 읽는다([P1.1-2](../platform/requirements.md#p11-경계-코드-수준)).

### R11. 운영 재투입·재발행 (신규)
**사용자 스토리:** 운영자로서, Studio가 낸 이벤트나 Studio가 받지 못한 결과를 같은 식별자로 다시 흘리고 싶다. 그래야 유실된 출시 흐름을 이을 수 있다.

#### 수용 기준
1. THE Studio SHALL 자기가 발행한 현행 이벤트 4종(`ProjectReviewRequested`·`ProjectReviewWithdrawalRequested`·`ProjectReleaseRequested`·`ProjectReleaseWithdrawalRequested`)을 ADMIN 운영 API로 같은 `eventId`·본문·키로 재발행할 수 있다([P2.2-7](../platform/requirements.md#p22-발행--transactional-outbox), [결정 161](../../../docs/decisions.md#republish-all-current)).
2. WHEN 소비 실패가 재시도를 소진하거나 영구 실패이면 THEN Studio SHALL 그 레코드를 Studio DB 실패 보관 표에 저장하고 같은 트랜잭션에서 (`studio-project`, 토픽, `productCode`)를 정지한다([P2.6-4·5](../platform/requirements.md#p26-재시도dlt), 결정 155·160).
3. WHEN ADMIN이 재투입하면 THEN Studio SHALL 같은 리스너 경로(`*Mapper.from` → 서비스)로 처리하고, 이미 반영된 결과는 Inbox·R7.4로 거른다.

## 범위 밖(후속)

- 심사 판정·공개 확정·공개 상품/상점 진열·파일 배포(Review·Catalog·Store·Download의 책임).
- 예약·출시 채널·승격·롤백, CI·OIDC 신뢰 정책·테스터·웹훅, 다중 자료 판본, 분할 업로드 세션.
- 공개 확정 전 철회의 시간 보장, 공개 후 되돌리기(결정 57·62).
- 대표 장르·선택 순위·장르 계층, 동일 자료 차단용 내용 해시·고비용 검증 캐시.
- DLC 모델·선물하기·구매와 사용권 등록 분리(결정 63의 확장 여지만 둔다).
- 창작자 통지 계약(심사·출시 지연 알림) — [결정 필요 M-ST-1].
- 검색·상태 필터, 목록 요약/상세 분리 — [결정 필요 M-ST-2].
