# 요구사항: Review (불변 Project 스냅샷의 수동 승인·반려와 결과 발행)

> 상태: 작성 중(결정 150·178). 모듈을 넘는 계약은 [플랫폼 스펙](../platform/requirements.md)이 정본이고 이 문서는 링크한다.

## 개요

Review는 Studio가 심사를 요청한 당시의 `ProjectSnapshot`(상세정보·전체 빌드)을 불변으로 받아 보관하고, 사람(REVIEWER·ADMIN)이 수동으로 승인 또는 반려한다. 판정과 철회 결과의 정본은 Review의 `project_review`다([플랫폼 design "정본 소유"](../platform/design.md#정본-소유) "심사 판정" 행). Review는 그 사실을 `stove.review.v1`에 낸다. 승인 사실은 Studio와 Catalog가 직접 받는다([결정 167](../../../docs/decisions.md#catalog-verifies-approval)).

Review는 Studio 작업본을 읽거나 바꾸지 않고, 심사 요청을 대신 만들지 않으며, 출시·상품 공개·판매·구매자 다운로드를 하지 않는다. 최초 심사와 재심사는 같은 접수·판정 흐름을 쓰고, 재심사는 언제나 Studio가 발급한 새 요청 ID다.

모듈을 넘는 흐름은 플랫폼 흐름 1의 S1(심사 요청)·S2(심사 철회)·S3(심사 판정)·S3a(승인 사실 보관)다([P3.2](../platform/requirements.md#p32-심사-요청철회-studio--review)·[P3.3](../platform/requirements.md#p33-심사-판정-결과-review--studio)·[P3.4-2](../platform/requirements.md#p34-출시-요청철회-studio--catalog), [design 흐름 1](../platform/design.md#흐름-1-출시--공개-studio--review--studio--catalog--store--download)). 이 문서는 그 계약을 Review가 어떻게 지키는지(리스너 → `*Mapper.from` → 서비스 → 상태 전이 → DB 제약 → Outbox)와 Review 자신의 API·상태 규칙만 적는다.

## 용어

- **`ProjectReview`**(`project_review`): 심사 요청 한 건. PK는 Studio가 발급한 `reviewRequestId`이며 Review는 자체 번호를 발급하지 않는다.
- **스냅샷**: 공통 계약 `ProjectSnapshot`(`common/kafka/.../contract/payload/ProjectSnapshot.java`) 전체. 받은 그대로 불변 JSON으로 보관한다. 값 동일성은 `ProjectSnapshot` 레코드 동등(장르는 정렬 뒤 비교)이다.
- **판정**: `REQUESTED`에서 `APPROVED` 또는 `REJECTED`로의 전이. 판정자 `actor`는 검증된 JWT `sub`다.
- **철회 명령**(`review_withdrawal`): Studio가 낸 `ProjectReviewWithdrawalRequested`의 내용(`withdrawalId`가 PK). 원요청보다 먼저 와도 보관한다.
- **원요청 없는 철회**: 보관했지만 같은 `reviewRequestId`의 `project_review`가 아직 없는 철회 명령([P3.2-7](../platform/requirements.md#p32-심사-요청철회-studio--review)).
- **요청 게이트**(`review_request_gate`): 원요청이 오기 전에도 존재하는 `reviewRequestId`별 잠금 대상 행.
- **REVIEWER / ADMIN**: Review 업무 API에 접근하는 두 역할. **CREATOR**는 결과를 Studio에서 보며 Review API에 접근하지 않는다.
- **영구 실패**: 재시도로 결과가 바뀌지 않는 소비 실패. 공통 영구 실패 예외로 표시하고 재시도 없이 실패 보관 표에 저장한다([P2.6-3·4](../platform/requirements.md#p26-재시도dlt), T20).

## 요구사항

### R1. 요청 접수와 불변 스냅샷

**사용자 스토리:** Review로서, Studio가 심사를 요청한 당시의 자료를 그대로 보존하고 싶다. 그래야 이후 작업본이 바뀌어도 요청 시점 기준으로 심사한다.

#### 수용 기준

1. WHEN Review가 `ProjectReviewRequested`를 그룹 `review-project`에서 처음 받으면 THEN Review SHALL `reviewRequestId`를 PK로 `project_review`를 `REQUESTED`로 만들고 받은 스냅샷 전체를 불변으로 저장한다([P3.2-2](../platform/requirements.md#p32-심사-요청철회-studio--review)).
2. THE Review SHALL 스냅샷의 빌드 ID·버전·저장 경로·크기·SHA-256과 `projectId`·`productCode`·`ownerSubject`·`contentVersion`을 최초 저장 값으로 유지하고 갱신하지 않는다(DB 열 `updatable=false`).
3. WHILE 심사가 진행 중인 동안 Studio 작업본이 바뀌어도 THE Review SHALL 원래 요청의 스냅샷으로 심사하고 Studio의 최신 Project를 읽지 않는다.
4. IF 이미 저장된 `reviewRequestId`에 값이 다른 스냅샷이 오면 THEN Review SHALL 기존 행을 덮어쓰지 않고 영구 실패로 거절한다([P2.3-4](../platform/requirements.md#p23-소비--inbox와-업무-멱등)·[P3.1-6](../platform/requirements.md#p31-흐름-공통-규칙)).
5. WHEN `*Mapper.from`이 레코드를 변환하면 THEN Review SHALL 봉투 `eventId`·`eventType`과 본문 값의 일치, Kafka 키 = 스냅샷 `productCode`, `schemaVersion`이 읽을 수 있는 버전인지, 필수 요청 ID·Project ID·버전·소유자·빌드 값의 형식을 확인하고, 하나라도 어기면 업무 처리 전에 영구 실패로 거절한다([P2.1-1·5](../platform/requirements.md#p21-봉투), [P2.7-2](../platform/requirements.md#p27-스키마-호환-결정-104)). Studio의 소유권·출시 조건은 다시 판정하지 않는다.
6. IF 같은 Project에 이미 `REQUESTED` 심사가 있는데 다른 `reviewRequestId`의 요청이 오면 THEN Review SHALL 새 행을 만들지 않고 영구 실패로 거절한다(`uk_review_active_project`, 결정 76, [P3.2-3](../platform/requirements.md#p32-심사-요청철회-studio--review)).

### R2. 수신 멱등과 트랜잭션 원자성

**사용자 스토리:** Review로서, 같은 이벤트가 여러 번 와도 심사를 한 건만 만들고 싶다. 그래야 재전달·재시작에도 결과가 흔들리지 않는다.

#### 수용 기준

1. WHEN 같은 `eventId`가 다시 오면 THEN Review SHALL 업무 트랜잭션 안의 Inbox(`processed_event`, 그룹 `review-project`)로 걸러 부수효과 없이 끝낸다([P2.3-1](../platform/requirements.md#p23-소비--inbox와-업무-멱등)).
2. WHEN 다른 `eventId`로 같은 `reviewRequestId`·같은 스냅샷이 오면 THEN Review SHALL 기존 심사를 유지하고 스냅샷·판정·최초 접수 정보(`requestEventId`·`requestedAt`)를 바꾸지 않는다(업무 멱등 키 = PK `review_request_id`, [P2.3-3](../platform/requirements.md#p23-소비--inbox와-업무-멱등)).
3. THE Review SHALL 요청 게이트 잠금·Inbox 표시·심사 생성·보관 철회 해결·결과 Outbox를 한 DB 트랜잭션으로 묶는다.
4. IF 그 트랜잭션의 어느 단계든 실패하면 THEN Review SHALL 성공 소비로 처리하지 않고 Inbox만 따로 커밋하지 않는다.
5. THE 소비 실패 SHALL 플랫폼 공통 재시도·실패 보관·키 정지를 따른다([P2.6](../platform/requirements.md#p26-재시도dlt), [결정 155](../../../docs/decisions.md#key-stop-on-dead)·[160](../../../docs/decisions.md#dead-letter-store)). R1.4·R1.5·R1.6·R6.10의 실패는 영구 실패다.

### R3. 제출 스냅샷·빌드 열람

**사용자 스토리:** 심사자로서, 제출된 상세정보와 전체 빌드를 안전하게 열람하고 싶다. 그래야 승인 여부를 판단한다.

#### 수용 기준

1. WHEN `GET /api/v1/reviews/{reviewRequestId}` 요청이 오면 THEN Review SHALL 제출 상세정보·빌드 버전·크기·체크섬·상태·판정·철회 기록을 주고 내부 저장 경로(`storagePath`)는 노출하지 않는다.
2. WHEN `GET /api/v1/reviews/{reviewRequestId}/build-download` 요청이 오면 THEN Review SHALL 그 스냅샷의 `storagePath`로 객체 저장소 읽기 전용 GET URL을 발급한다.
3. THE 발급 URL SHALL 설정한 수명(기본 5분, 1초~15분) 동안 유효하며, 응답은 `url`·`expiresAt`·`fileSize`·제출 `checksum`을 함께 싣는다.
4. IF 스냅샷 경로가 설정 버킷 밖이거나, 객체가 없거나, 객체 크기가 스냅샷 `fileSize`와 다르면 THEN Review SHALL URL을 발급하지 않고 409로 응답하며 심사·스냅샷은 바꾸지 않는다. 저장소 일시 장애는 503이다.
5. THE 클라이언트 SHALL `reviewRequestId`만 보내며, URL·버킷·객체 경로를 입력하지 않는다.
6. THE Review SHALL 서명 URL을 캐시하거나 로그에 남기지 않고(`Cache-Control: no-store`), 저장소에는 객체 읽기·메타 조회 권한만 쓴다. 업로드·삭제·버킷 생성을 하지 않는다.
7. IF 열람 실패·저장소 장애가 나면 THEN Review SHALL 그것을 승인·반려·철회로 바꾸지 않는다.
8. THE 열람 SHALL 심사 상태와 무관하게 같은 규칙을 쓴다. 종료된 심사(`APPROVED`·`REJECTED`·`WITHDRAWN`)의 새 URL 발급 허용 범위는 [결정 필요 M-RV-4]다.

### R4. 수동 승인·반려

**사용자 스토리:** 심사자로서, 열람한 뒤 승인하거나 사유를 적어 반려하고 싶다. 그래야 판정이 근거와 함께 남는다.

#### 수용 기준

1. WHEN `POST /api/v1/reviews/{reviewRequestId}/approve` 요청이 오면 THEN Review SHALL `REQUESTED` 심사를 `APPROVED`로 바꾼다.
2. WHEN `POST /api/v1/reviews/{reviewRequestId}/reject` 요청이 오면 THEN Review SHALL 필수 `feedback`(공백 아님, 최대 2000자)과 함께 `REJECTED`로 바꾼다.
3. THE Review SHALL `project_review` 행 잠금 안에서 상태·`actor`·`decidedAt`·결과 Outbox(`ProjectReviewApproved`/`ProjectReviewRejected`)를 한 트랜잭션에 저장한다([P3.3-1](../platform/requirements.md#p33-심사-판정-결과-review--studio)).
4. THE `actor` SHALL 검증된 JWT `sub`이고, `actor`·판정 시각·`projectId`·`buildId`·`contentVersion`은 요청 본문으로 받지 않는다. `decidedAt`은 Review 시계로 정한다.
5. WHERE BFF 쿠키를 쓰는 POST이면 THE Review SHALL Gateway의 CSRF 계약을 따른다(Review 자체는 Bearer 리소스 서버, CSRF는 Gateway가 검사).
6. THE 판정 성공 응답(200) SHALL Review 확정만 뜻하고 Studio·Catalog 반영 완료를 뜻하지 않는다.

### R5. 판정 멱등·확정 불변·동시성

**사용자 스토리:** Review로서, 같은 판정의 재시도는 안전하게 흡수하고 확정 판정은 뒤집지 않고 싶다. 그래야 결과가 한 번만 확정된다.

#### 수용 기준

1. WHEN 이미 확정된 심사에 같은 판정·같은 반려 사유가 다시 오면 THEN Review SHALL 기존 결과를 200으로 돌려주고 새 결과 이벤트를 내지 않으며 최초 `actor`·`decidedAt`을 유지한다.
2. IF 반대 판정이거나 확정된 반려 사유를 바꾸는 요청이면 THEN Review SHALL 409로 거절하고 기존 판정을 유지한다.
3. WHILE 승인·반려·철회가 같은 요청에 동시에 들어오는 동안 THE Review SHALL `project_review` 행 잠금으로 직렬화해 하나만 확정한다(결정 53: 먼저 커밋된 결과).
4. THE Review SHALL 확정된 판정·철회를 뒤집거나 다시 여는 API·운영 명령·이벤트 처리를 두지 않는다. 그래서 한 `reviewRequestId`에 `ProjectReviewApproved`는 많아야 한 사실이고, 한 번 낸 승인은 이후 철회·반려로 바뀌지 않는다(R9.3의 근거, (1회차 지적 B3, 판정 전)).

### R6. 재심사와 철회

**사용자 스토리:** 창작자로서, 반려 후 다시 심사받거나 판정 전 요청을 철회하고 싶다. 그래야 자료를 고쳐 재신청하거나 요청을 거둘 수 있다.

#### 수용 기준

1. WHEN 반려·승인·철회 뒤 재심사가 필요하면 THEN Review SHALL Studio가 발급한 새 `reviewRequestId`로 새 심사를 만들고 과거 스냅샷·판정·철회 기록을 유지한다.
2. WHERE 철회 뒤 같은 자료로 재신청하면 THE Review SHALL 자료가 같아도 새 요청 ID로 새 심사를 접수하며 이전 심사를 다시 열거나 철회 기록을 지우지 않는다([결정 59](../../../docs/decisions.md#review-withdrawal-allow-unchanged-resubmission)).
3. WHEN `ProjectReviewWithdrawalRequested`를 그룹 `review-project`에서 받으면 THEN Review SHALL 요청 게이트 잠금 → `project_review` 행 잠금 순서로, 판정과 같은 행 잠금 안에서 처리한다([P3.2-5](../platform/requirements.md#p32-심사-요청철회-studio--review)).
4. WHILE 심사가 `REQUESTED`이면 THE Review SHALL 검토 착수 여부와 무관하게 철회를 허용한다([결정 60](../../../docs/decisions.md#review-withdrawal-until-final-decision)).
5. WHEN 철회가 먼저 커밋되면 THEN Review SHALL 이후 승인·반려를 409로 거절하고, 판정이 먼저 커밋되면 이후 철회가 그 판정을 바꾸지 않는다([결정 53](../../../docs/decisions.md#review-withdrawal-first-committed-result)).
6. THE 철회 결과 SHALL 두 사실로 나눠 같은 트랜잭션의 Outbox에 낸다: 철회가 이겨 `WITHDRAWN`이 되면 `ProjectReviewWithdrawn`, 판정이 먼저 확정돼 철회가 거절되면 `ProjectReviewWithdrawalDenied`(`targetStatus` ∈ {`APPROVED`,`REJECTED`}). `…WithdrawalDenied`는 대상 판정의 `actor`·`feedback`을 싣지 않는다([결정 176](../../../docs/decisions.md#withdrawal-result-events), [P3.2-5](../platform/requirements.md#p32-심사-요청철회-studio--review), T21).
7. WHEN 철회가 원요청보다 먼저 오면 THEN Review SHALL 철회 명령을 `review_withdrawal`에 미해결로 보관하고 결과 이벤트를 내지 않으며, 원요청 접수 트랜잭션에서 같은 `reviewRequestId`로 대조해 `WITHDRAWN`을 확정하고 `ProjectReviewWithdrawn`을 낸다. 원요청 없이 심사 행을 만들지 않는다([P3.2-6](../platform/requirements.md#p32-심사-요청철회-studio--review)).
8. THE 철회 완료 SHALL `WITHDRAWN`이며 반려가 아니다. `withdrawnBy`·`withdrawalReason`(선택)·`withdrawnAt`을 판정 칸(`actor`·`feedback`·`decidedAt`)과 따로 기록하고, `withdrawnAt`은 Review 시계로 정한다.
9. WHEN 같은 `withdrawalId`의 철회가 다른 `eventId`로 다시 오면 THEN Review SHALL 내용이 같으면 최초 결과로 수렴하고(새 결과 없음, 미해결이면 해결 시도) 끝난 요청을 다시 열지 않는다. WHEN `WITHDRAWN`인 요청에 다른 `withdrawalId`의 철회가 오면 THEN Review SHALL 상태를 바꾸지 않고 `ProjectReviewWithdrawn`을 그 `withdrawalId`로 다시 낸다(Studio 명령마다 응답 하나).
10. IF 같은 `withdrawalId`에 내용이 다른 명령이 오거나, 철회 대상(`projectId`·`contentVersion`·`productCode`·`ownerSubject`)이 `project_review`와 다르면 THEN Review SHALL 기존 기록을 바꾸지 않고 영구 실패로 거절한다. WHEN 원요청보다 먼저 보관한 철회가 나중에 온 원요청과 대상이 다르면 THEN Review SHALL 원요청은 평소대로 접수하고, 그 철회는 같은 트랜잭션에서 `CONFLICTED`로 바꾸며 적용하지 않는다. 결과 이벤트는 내지 않고, `CONFLICTED` 철회 건수를 게이지로 내 1건부터 경보한다([결정 183](../../../docs/decisions.md#isolate-conflicted-withdrawal)).

### R7. 접근 권한

**사용자 스토리:** 플랫폼으로서, 심사 자료와 판정을 심사 권한자에게만 열고 싶다. 그래야 판정자가 위조되지 않는다.

#### 수용 기준

1. WHERE 모든 업무 API(`/api/v1/reviews/**`)에서 THE Review SHALL Gateway와 Review 양쪽에서 `REVIEWER` 또는 `ADMIN`을 요구한다.
2. IF 익명이거나 `REVIEWER`·`ADMIN`이 없는 `MEMBER`·`CREATOR` 요청이면 THEN Review SHALL 거절한다(익명 401, 권한 부족 403).
3. THE 판정자 SHALL 요청 본문이 아니라 검증된 JWT `sub`이며 본문으로 위조할 수 없다(`FAIL_ON_UNKNOWN_PROPERTIES`로 본문의 모르는 칸도 400).
4. WHERE 초기 범위이면 THE Review SHALL 심사자 배정 없이 `REVIEWER`·`ADMIN`이 모든 접수 건을 조회·판정하게 한다.
5. THE 운영 API(실패 보관 재투입, Outbox `DEAD` 회수, 재발행) SHALL `ADMIN`만 쓰고, 키 단위로만 회수한다([P2.6-5](../platform/requirements.md#p26-재시도dlt)·[P2.2-7](../platform/requirements.md#p22-발행--transactional-outbox)).

### R8. 목록·결과 이벤트 계약

**사용자 스토리:** 심사자로서, 접수 건을 상태·프로젝트로 찾고 결과를 정확히 전달하고 싶다. 그래야 운영과 창작자 쪽 반영이 맞는다.

#### 수용 기준

1. WHEN `GET /api/v1/reviews` 요청이 오면 THEN Review SHALL 상태·Project 필터와 페이지 조회를 준다(기본 page=0, size=20, 최대 size=100, `requestedAt` 내림차순 다음 `reviewRequestId` 내림차순). `requestedAt`(Studio 사실 시각)은 표시·정렬에만 쓰고 판정에 쓰지 않는다([P2.1-3](../platform/requirements.md#p21-봉투)).
2. THE Review SHALL `stove.review.v1`에 다음 사실만 낸다: `ProjectReviewApproved`·`ProjectReviewRejected`(판정)·`ProjectReviewWithdrawn`·`ProjectReviewWithdrawalDenied`(철회 결과). 각 행의 뜻·소비자·키·순서는 [플랫폼 design 이벤트 목록](../platform/design.md#eventtype에-없는-현행-이벤트-문자열-리터럴-10개-t26)·["목표 행"](../platform/design.md#목표-행-아직-코드에-없음)이 정본이다. 이벤트 타입은 `EventType` 상수로 쓴다([P2.1-4](../platform/requirements.md#p21-봉투), T26).
3. THE 결과 이벤트의 `reviewRequestId`·`projectId`·`contentVersion`·`productCode` SHALL 저장된 `project_review` 값이고, 판정 이벤트의 `actor`는 검증된 JWT `sub`, 반려 `feedback`은 확정한 사유다([P3.3-2](../platform/requirements.md#p33-심사-판정-결과-review--studio)).
4. THE Kafka 키와 Outbox `aggregateId` SHALL `productCode`다([P2.4-1](../platform/requirements.md#p24-파티션-키)).
5. WHEN 잘못된 입력·미접수 요청·확정 충돌이면 THEN Review SHALL 각각 400·404·409로 응답한다.
6. THE Review SHALL 발행·소비가 없는 옛 Review 계약 상수(`ReviewApproved`·`ReviewRejected`·`SubmissionReviewApproved`·`ReviewChangesRequested`·`ReviewAppealed`)를 내지 않으며, 제거 PR에 Review `outbox_event`·실패 보관 표에 그 타입 행이 없다는 근거를 낸다([결정 172](../../../docs/decisions.md#remove-legacy-contracts), [P2.8-2](../platform/requirements.md#p28-구-계약-처리)).
7. IF `stove.studio.v1`에서 Review가 처리하지 않는 `eventType`이 오면 THEN Review SHALL 부수효과 없이 건너뛰고 (그룹 `review-project`, 타입)별 건너뜀 수를 지표로 낸다([P2.3-7](../platform/requirements.md#p23-소비--inbox와-업무-멱등), T31).

### R9. 승인 사실의 발행 계약 (Catalog 대조용, 결정 167)

**사용자 스토리:** 공개를 확정하는 Catalog로서, 정본인 Review에게서 승인 사실을 직접 받아 출시 요청과 대조하고 싶다. 그래야 Studio 결함·위조·수동 수정으로 승인 안 된 자료가 판매되지 않는다.

#### 수용 기준

1. THE `ProjectReviewApproved` SHALL Catalog가 [P3.4-2](../platform/requirements.md#p34-출시-요청철회-studio--catalog)의 대조(`reviewRequestId`, `projectId`, `contentVersion`, 스냅샷 값)를 다른 모듈에 묻지 않고 할 수 있는 값을 싣는다. 그 값은 Review가 `project_review`에 저장한 `ProjectSnapshot` 전체(`snapshot` 칸)이고, 저장한 값을 바꾸거나 다시 만들지 않는다. 지문·해시는 싣지 않는다([결정 181](../../../docs/decisions.md#approval-snapshot-payload)).
2. WHEN 승인 이벤트 본문 모양을 바꾸면 THEN Review SHALL 같은 `eventType`의 새 `schemaVersion`으로 내고 P2.7-5 배포 순서(소비자 Studio·Catalog가 옛·새 버전을 모두 읽은 뒤 발행 전환)를 따른다([결정 170](../../../docs/decisions.md#schema-version-header)).
3. THE Review SHALL 한 `reviewRequestId`에 승인 사실을 많아야 하나 내고, 승인 뒤 그 심사를 `WITHDRAWN`·`REJECTED`로 바꾸지 않는다(R5.4). 승인 사실이 나간 뒤 Catalog 쪽 출시가 철회·거절·종결된 경우의 처리는 Catalog가 정한다(Review는 승인을 다시 내거나 거두지 않는다, (1회차 지적 B3, 판정 전)).
4. THE Review SHALL 이 계약 이전에 이미 `APPROVED`인 심사의 승인 사실을 다시 내지 않는다(이행 명령 없음, RV-I12 예외 없음). 그 승인을 근거로 한 출시는 Catalog 승인 대기에 남고, 창작자는 새 심사를 요청해 v2 승인을 받는다. 보존할 운영 데이터가 없다는 전제이며, 운영 데이터가 생긴 뒤 승인 이벤트 모양을 다시 바꾸면 이 항목을 다시 연다([결정 182](../../../docs/decisions.md#no-approval-migration)).

### R10. 원요청 없는 철회와 운영 경로

**사용자 스토리:** 운영자로서, Review가 응답하지 못하고 쥐고 있는 기록과 실패한 레코드를 Review 안에서 세고 되돌리고 싶다. 그래야 모듈 간 반영 누락이 조용히 남지 않는다.

#### 수용 기준

1. THE Review SHALL 원요청 없는 철회(`review_withdrawal` 미해결이고 같은 `request_id`의 `project_review`가 없음)의 건수와 최장 대기 시간을 게이지로 낸다. 대기 시간은 Review 시계(`review_withdrawal.created_at`)로 재고, Studio가 찍은 `requested_at`과 비교하지 않는다([P3.2-7](../platform/requirements.md#p32-심사-요청철회-studio--review), [결정 158](../../../docs/decisions.md#forward-gap-counting)). 경보 기준 시간은 [결정 159](../../../docs/decisions.md#forward-gap-threshold)대로 측정 뒤 정한다.
2. THE Review SHALL 소비 실패 레코드를 `stove_review`의 실패 보관 표에 기한 없이 저장하고 같은 트랜잭션에서 (그룹, 토픽, 키)를 정지하며, ADMIN 재투입은 같은 리스너 경로(`StudioEventListener` → `*Mapper.from` → 서비스)로 한다([P2.6-4·5](../platform/requirements.md#p26-재시도dlt), [결정 160](../../../docs/decisions.md#dead-letter-store)).
3. THE Review SHALL 자기가 낸 현행 이벤트(R8.2)를 같은 `eventId`·본문·키로 다시 내는 재발행 경로(`outbox_republish`, `republish-owner`, ADMIN API)를 가진다([P2.2-7](../platform/requirements.md#p22-발행--transactional-outbox), [결정 161](../../../docs/decisions.md#republish-all-current)). 재발행 레코드가 정지된 키로 들어갔을 때의 회수 정렬은 플랫폼 계약을 따른다(1회차 지적 B1, 판정 전).
4. THE 운영 API 활성화 SHALL 운영 설정으로 정하고, 테스트 전용 경로·기본값을 두지 않는다(원칙 ⑦).

## 범위 밖(후속)

- 자동 심사·다단계 관문·담당 배정·SLA·체크리스트·등급·이의 제기(구 흐름과 함께 제거).
- 승인·반려를 뒤집는 재판정 예외 API, 관리자 강제 철회.
- 심사 목록/상태 조회의 사용성 개선, 진행 중 요청 보류·창작자 통지 계약(G4).
- 이미 발급한 서명 URL·받은 파일의 회수(상태 변경으로 회수할 수 없다).
