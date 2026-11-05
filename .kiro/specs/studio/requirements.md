# 요구사항: Studio (출시 상세정보·빌드·심사/출시 요청과 철회)

## 개요

Studio는 창작자(CREATOR)가 게임을 출시하기 위해 상세정보·전체 빌드를 관리하고, 심사 요청·재요청과 출시 요청·재요청을 발행하는 작업 도구다.
심사 판정과 실제 출시·공개 상품 관리는 외부 업무이며 Studio는 요청과 외부 결과를 기록만 한다.
소유자는 Auth JWT의 sub로 직접 식별하며 별도 판매자 매핑은 없다.
포트 8085, 저장소 MySQL. 2026-10-14 재작성을 완료하고 이후 목록 페이지·장르 분류·단일 심사/출시와 명시적 철회를 더해 검증했다.

## 용어

- **CREATOR**: 창작자 역할. Project 생성·상세정보 관리·심사/출시 요청에 필요.
- **ownerSubject**: Auth JWT의 sub. Project 소유자를 직접 식별한다.
- **contentVersion**: 상세정보 작업본의 버전. 상점 정보(장르 포함)가 바뀌면 증가한다.
- **활성 요청(activeReviewRequest / activeReleaseRequest)**: 프로젝트 전체에서 진행 중인 심사·출시 요청. 작업본 버전과 독립적으로 조회한다.
- **스냅샷**: 요청 발행 시점의 상세정보·빌드를 고정한 불변 자료. 변경하지 않는다.
- **releaseEligible**: 현재 상세정보 버전의 외부 심사 승인 결과로부터 계산한 출시 요청 가능 여부.

## 요구사항

### R1. 상세정보·빌드·썸네일 관리
**사용자 스토리:** 창작자로서, 출시용 상세정보와 전체 빌드를 올리고 싶다. 그래야 심사·출시를 요청할 수 있다.

#### 수용 기준
1. WHEN CREATOR가 Project를 생성하면 THEN 시스템 SHALL ownerSubject·productCode와 함께 201로 만든다.
2. WHERE 상세정보 관리 맥락에서 THE 시스템 SHALL 제목·짧은 소개·썸네일·선택적 HTTP/HTTPS 영상 URL·내용·KRW 정수 가격을 보관한다.
3. WHEN 상점 정보가 실제로 바뀌면 THEN 시스템 SHALL contentVersion을 증가시킨다.
4. THE 전체 빌드 SHALL ZIP 한 개이며 기본 1GiB, PNG/JPEG 썸네일은 기본 5MiB·8192픽셀을 상한으로 둔다.
5. WHEN 파일을 업로드하면 THEN 시스템 SHALL 임시 디스크에서 검사·SHA-256 계산 후 고유 S3/MinIO 객체로 저장한다.
6. IF 저장 DB 트랜잭션이 실패하면 THEN 시스템 SHALL 새 객체를 정리하고, 요청에 첨부한 기존 파일은 덮어쓰거나 삭제하지 않는다.

### R2. 장르 선택
**사용자 스토리:** 창작자로서, 플랫폼이 정한 장르로 상품을 분류하고 싶다. 그래야 탐색과 입력이 일관된다.

#### 수용 기준
1. WHERE 작업본 저장 맥락에서 THE 시스템 SHALL 장르를 0~3개 허용한다(미선택 저장 가능).
2. WHEN 새 심사를 제출하면 THEN 시스템 SHALL 현재 선택 가능한 장르 코드 1~3개를 요구한다.
3. IF 입력 장르가 중복·알 수 없는 코드·상한 초과이면 THEN 시스템 SHALL 거절한다.
4. WHERE 장르만 수정하는 맥락에서 THE 시스템 SHALL 기존 상점 정보의 승인/출시 계약을 적용하며 새 버전 심사·출시를 거치게 한다.
5. WHEN 알려진 장르가 나중에 선택 중지돼도 THEN 시스템 SHALL 이미 제출한 근거의 읽기·출시를 유지하고 새 심사 제출만 선택 가능한 장르로 제한한다.
6. WHERE CREATOR 장르 기준 조회 맥락에서 THE 시스템 SHALL `GET /api/v1/studio/genres`로 코드·표시명·선택 가능 여부를 제공한다.

### R3. 심사 요청·재요청
**사용자 스토리:** 창작자로서, 현재 상세정보·빌드로 심사를 요청하고 싶다. 그래야 출시 조건을 확인할 수 있다.

#### 수용 기준
1. WHEN `POST /api/v1/studio/projects/{id}/review-requests`가 requestId·contentVersion으로 오면 THEN 시스템 SHALL 현재 자료를 불변 스냅샷으로 고정하고 202로 접수한다.
2. THE 심사 제출 본문 SHALL requestId와 contentVersion을 필수로 요구한다.
3. WHEN 같은 명령(동일 requestId)이 재전송되면 THEN 시스템 SHALL 최초 접수 요청의 영수증을 반환하고 현재 작업본을 새로 제출하지 않는다.
4. IF 같은 requestId에 다른 대상·내용이 오면 THEN 시스템 SHALL 충돌로 거절한다.
5. WHEN 반려 후 재요청하면 THEN 시스템 SHALL 새 ID와 새 요청 이벤트로 발행하고 이전 이력을 유지한다.
6. THE 시스템 SHALL `ProjectReviewRequested`를 Outbox로 발행한다.

### R4. 출시 요청·재요청
**사용자 스토리:** 창작자로서, 승인된 상세정보를 출시 요청하고 싶다. 그래야 공개로 이어진다.

#### 수용 기준
1. WHEN `POST /api/v1/studio/projects/{id}/release-requests`가 requestId·contentVersion으로 오면 THEN 시스템 SHALL 현재 작업본 버전과 일치하는 승인 스냅샷으로 출시 요청을 202로 발행한다.
2. IF 현재 버전의 외부 심사 승인 결과가 없으면 THEN 시스템 SHALL 출시 요청을 인정하지 않는다.
3. THE 다른 버전의 승인 결과 SHALL 현재 버전의 출시 요청 조건을 충족하지 않는다.
4. WHEN 같은 명령이 재전송되면 THEN 시스템 SHALL 기존 요청을 반환하고 새 공개를 만들지 않는다.
5. THE 시스템 SHALL `ProjectReleaseRequested`를 Outbox로 발행한다.

### R5. 프로젝트 전체 단일 심사·단일 출시
**사용자 스토리:** 창작자로서, 진행 중인 요청이 명확하길 바란다. 그래야 중복 제출을 피한다.

#### 수용 기준
1. THE 시스템 SHALL 프로젝트의 모든 버전을 통틀어 진행 중 심사를 1건으로 제한한다.
2. THE 시스템 SHALL 프로젝트의 모든 버전을 통틀어 진행 중 출시를 1건으로 제한한다.
3. WHILE 철회를 접수·처리하는 동안 THE 시스템 SHALL 해당 활성 요청의 자리를 유지한다.
4. WHERE 활성 요청 조회 맥락에서 THE 시스템 SHALL activeReviewRequest·activeReleaseRequest를 작업본 버전과 독립적으로 제공한다.
5. WHILE 이전 출시를 처리하는 동안 THE 시스템 SHALL 다음 버전의 편집·심사를 허용한다.
6. WHEN 동시 생성·중복 접수가 들어오면 THEN 시스템 SHALL 공통 Project 잠금과 DB 유일 키로 단일 요청을 보장한다.

### R6. 심사·출시 철회 (내구 접수)
**사용자 스토리:** 창작자로서, 잘못 보낸 요청을 명시적으로 철회하고 싶다. 그래야 다시 올바르게 제출할 수 있다.

#### 수용 기준
1. WHEN 철회가 명시적 대상 요청과 선택 reason으로 오면 THEN 시스템 SHALL 202로 내구 접수하고 no-store 영수증을 제공한다.
2. THE 철회 영수증 SHALL PENDING에서 WITHDRAWN 또는 DENIED로 확정되며 실제 정본 결과·행위자·시각·사유를 보존한다.
3. THE 철회 접수 SHALL 최종 성공을 보장하지 않는다(접수와 완료를 구분).
4. WHEN 심사 철회가 확정되고 Review의 최종 결과가 확인되면 THEN 시스템 SHALL 동일 자료의 새 심사를 새 requestId로 허용한다.
5. WHEN 출시 철회가 공개 확정 전에 확정되면 THEN 시스템 SHALL 기존 승인본을 새 명령으로 다시 출시 요청하도록 허용한다.
6. WHERE 소유권 맥락에서 THE 시스템 SHALL CREATOR sub·프로젝트 소유권·대상 요청의 프로젝트 일치를 확인하고 철회 영수증 조회를 소유자·명시적 대상에 제한한다.
7. THE 시스템 SHALL `ProjectReviewWithdrawalRequested`·`ProjectReleaseWithdrawalRequested`를 발행한다.

### R7. 외부 결과 수신과 요청 상태
**사용자 스토리:** 창작자로서, 외부 심사·출시 결과를 요청 이력에서 확인하고 싶다. 그래야 다음 행동을 정할 수 있다.

#### 수용 기준
1. WHEN 외부 결과가 오면 THEN 시스템 SHALL 요청 ID·Project·버전이 맞는 결과만 해당 요청에 기록한다.
2. THE 심사 요청 상태 SHALL REQUESTED·APPROVED·REJECTED·WITHDRAWN 중 하나다.
3. THE 출시 요청 상태 SHALL REQUESTED·COMPLETED·REJECTED 중 하나다.
4. WHEN 처리된 요청의 뒤늦은 상충 결과·중복 이벤트가 오면 THEN 시스템 SHALL 기존 기록·새 요청을 덮어쓰지 않는다.
5. WHEN 상세정보 수정 후 과거 결과가 도착하면 THEN 시스템 SHALL 과거 요청에만 기록한다.
6. THE 시스템 SHALL `ProjectReviewApproved/Rejected`·`ProjectReleaseCompleted/Rejected`와 각 `WithdrawalResolved`를 수신한다.

### R8. 목록 페이지 조회
**사용자 스토리:** 창작자로서, 프로젝트·빌드·요청 이력을 페이지로 조회하고 싶다. 그래야 이력이 늘어도 다룰 수 있다.

#### 수용 기준
1. THE 프로젝트·빌드·심사/출시 이력의 GET 목록 SHALL 기본 page=0·size=20, 최대 100, ID 내림차순이다.
2. THE 응답 `data` SHALL `items/page/size/totalElements/totalPages` 구조다.
3. IF 음수 페이지·범위 밖 크기·숫자 아닌 인자·JPA 정수 offset 초과이면 THEN 시스템 SHALL 400으로 거절한다.
4. WHERE 조회 한도 맥락에서 THE 시스템 SHALL 소유자·프로젝트 조건을 count 조회에도 적용하고 DB에서 조회량을 제한한다.
5. THE 철회 영수증 목록 SHALL 기존 제한 조회·응답 계약을 유지한다.

### R9. 소유권과 공개 경계
**사용자 스토리:** 창작자로서, 내 자료에만 접근이 허용되길 바란다. 그래야 자료가 안전하다.

#### 수용 기준
1. WHERE `/api/v1/studio/**`(공개 썸네일 제외) 맥락에서 THE 시스템 SHALL CREATOR 역할과 소유권을 확인한다.
2. WHERE `GET /api/v1/studio/assets/{projectId}/{assetId}` 맥락에서 THE 시스템 SHALL 공개 썸네일 조회를 허용한다.
3. THE Project SHALL APPROVED·PUBLISHED 상태나 공개본 참조, `/publish` API를 두지 않는다.

## 범위 밖(외부 업무·후속)

- 심사 판정·실제 출시·공개 상품/상점 진열·파일 배포(각각 Review·Catalog·Store의 책임).
- 예약·출시 채널·승격·롤백, CI·OIDC 신뢰 정책·테스터·웹훅, 다중 자료 판본, 분할 업로드 세션.
- 공개 확정 전 철회의 시간 보장, 공개 후 되돌리기.
- 대표 장르·선택 순위·장르 계층, 동일 자료 차단용 내용 해시·고비용 검증 캐시.
- DLC 모델·선물하기·구매와 사용권 등록 분리.
