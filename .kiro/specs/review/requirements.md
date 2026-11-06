# 요구사항: Review (제출 스냅샷·수동 승인/반려)

## 개요

Review는 Studio가 심사를 요청한 당시의 상세정보·전체 빌드를 불변 스냅샷으로 받아,
사람(REVIEWER·ADMIN)이 수동으로 승인 또는 반려하고 그 판정의 원본과 결과 이벤트를 관리한다.
최초 심사와 수정 후 재심사는 같은 접수·판정 흐름을 쓰며, 재심사는 항상 새 요청이다.
Review는 실제 출시·상품 공개·상점 진열·판매·구매자 다운로드를 수행하지 않는다.
요청 단위 ProjectReview 한 모델로 통합한 MVP 재작성은 2026-10-14 완료했다(단위·아키텍처·권한 50건, 통합 12건, E2E 10건 등 통과).

## 용어

- **ProjectReview**: 한 건의 심사 요청. `reviewRequestId`를 PK로 사용한다.
- **reviewRequestId**: Studio가 발급한 요청 ID. Review가 자체 번호를 발급하지 않는다.
- **스냅샷(ProjectSnapshot)**: 요청 당시의 상세정보·빌드 정보 전체. 불변 JSON으로 보관한다.
- **판정(actor)**: 최종 승인/반려를 수행한 심사자의 JWT `sub`.
- **REVIEWER / ADMIN**: 심사·운영 역할. 두 역할만 Review 업무 API에 접근한다.
- **CREATOR**: 창작자. 결과는 Studio에서 조회하며 Review 운영 API에 접근하지 않는다.

## 요구사항

### R1. 요청 접수와 불변 스냅샷
**사용자 스토리:** Review로서, Studio가 심사를 요청한 당시의 자료를 그대로 보존하고 싶다. 그래야 이후 작업본이 바뀌어도 요청 시점 기준으로 심사한다.

#### 수용 기준
1. WHEN `ProjectReviewRequested` 이벤트를 받으면 THEN 시스템 SHALL 요청 당시 스냅샷을 한 건의 `ProjectReview`로 `REQUESTED` 상태로 저장한다.
2. THE 시스템 SHALL 받은 `ProjectSnapshot` 전체를 불변 JSON으로 보관하며, 빌드 ID·버전·경로·크기·SHA-256은 최초 제출 값을 유지한다.
3. WHERE 심사 중 Studio 작업본이 바뀐 맥락에서 THE 시스템 SHALL 원래 요청의 스냅샷을 계속 심사하고 Studio의 최신 Project로 스냅샷을 갱신하지 않는다.
4. WHEN 같은 요청 ID에 다른 스냅샷이 수신되면 THEN 시스템 SHALL 이를 계약 위반으로 처리하고 기존 자료를 덮어쓰지 않는다.
5. THE 시스템 SHALL 수신 봉투·본문의 이벤트 ID·유형, Kafka 키와 `productCode`의 일치, 필수 요청 ID·Project ID·버전·소유자·빌드 정보가 현재 계약에 맞는지 확인하며, Studio의 소유권·출시 조건을 다시 판정하지 않는다.

### R2. 수신 멱등과 트랜잭션 원자성
**사용자 스토리:** Review로서, 같은 이벤트가 여러 번 와도 심사를 한 건만 만들고 싶다. 그래야 재전달·재시작에도 결과가 흔들리지 않는다.

#### 수용 기준
1. WHEN 동일 이벤트가 재수신되면 THEN 시스템 SHALL 같은 업무 트랜잭션의 Inbox로 중복을 제거한다.
2. WHEN 다른 이벤트 ID로 동일 요청·동일 스냅샷이 수신되면 THEN 시스템 SHALL 기존 심사를 유지하고 스냅샷·판정·최초 접수 정보를 바꾸지 않는다.
3. THE 시스템 SHALL Inbox 마킹과 심사 접수를 하나의 DB 트랜잭션으로 묶는다.
4. IF 심사 접수가 실패하면 THEN 시스템 SHALL 이를 성공 소비로 처리하거나 Inbox만 별도 커밋하지 않는다.
5. WHERE 잘못된 계약·처리 실패 맥락에서 THE 시스템 SHALL 기존 공통 Kafka 재시도·DLT 경계를 사용한다.

### R3. 제출 스냅샷·빌드 열람
**사용자 스토리:** 심사자로서, 제출된 상세정보와 전체 빌드를 안전하게 열람하고 싶다. 그래야 승인 여부를 판단한다.

#### 수용 기준
1. WHEN `GET /api/v1/reviews/{reviewRequestId}` 요청이 오면 THEN 시스템 SHALL 제출 상세정보·빌드 버전·크기·체크섬·판정을 제공하고 내부 저장 경로는 노출하지 않는다.
2. WHEN `GET /api/v1/reviews/{reviewRequestId}/build-download` 요청이 오면 THEN 시스템 SHALL 해당 스냅샷의 `storagePath`로 읽기 전용 S3/MinIO GET URL을 발급한다.
3. THE 발급 URL SHALL 기본 5분 유효하며, `expiresAt`·`fileSize`·제출 `checksum`을 함께 반환한다.
4. IF 객체가 누락되었거나 크기가 불일치하거나 버킷이 다르면 THEN 시스템 SHALL URL을 발급하지 않고 409로 응답하며 접수된 심사·스냅샷은 보존한다. 저장소 일시 장애는 503이다.
5. THE 클라이언트 SHALL `reviewRequestId`만 전달하며, 임의 URL·버킷·객체 경로를 입력하지 않는다.
6. THE 시스템 SHALL 서명 URL을 캐시하거나 로그에 남기지 않으며, 저장소에는 읽기·조회 권한만 사용하고 업로드·삭제·버킷 생성을 수행하지 않는다.
7. IF 파일 열람 실패·저장소 일시 장애가 발생하면 THEN 시스템 SHALL 이를 승인·반려 판정으로 자동 변환하지 않는다.

### R4. 수동 승인·반려
**사용자 스토리:** 심사자로서, 열람한 뒤 승인하거나 사유를 적어 반려하고 싶다. 그래야 판정이 근거와 함께 남는다.

#### 수용 기준
1. WHEN `POST /api/v1/reviews/{reviewRequestId}/approve` 요청이 오면 THEN 시스템 SHALL `REQUESTED` 심사를 `APPROVED`로 전이한다.
2. WHEN `POST /api/v1/reviews/{reviewRequestId}/reject` 요청이 오면 THEN 시스템 SHALL 필수 `feedback`(최대 2000자)과 함께 `REJECTED`로 전이한다.
3. THE 시스템 SHALL 판정 기록(상태·`actor`·`decidedAt`)과 결과 Outbox를 같은 DB 트랜잭션에서 저장한다.
4. THE `actor` SHALL 검증된 JWT `sub`로 기록하며, `actor`·판정 시각·`projectId`·`buildId`·`contentVersion`은 요청 본문으로 받지 않는다.
5. WHERE BFF 쿠키를 사용하는 POST 맥락에서 THE 시스템 SHALL 기존 Gateway의 CSRF 계약을 따른다.

### R5. 판정 멱등·확정 불변·동시성
**사용자 스토리:** Review로서, 같은 판정의 재시도는 안전하게 흡수하고 확정 판정은 뒤집지 않고 싶다. 그래야 결과가 한 번만 확정된다.

#### 수용 기준
1. WHEN 동일한 최종 판정·반려 사유가 재전송되면 THEN 시스템 SHALL 기존 결과를 반환하고 새 결과 이벤트를 발행하지 않으며 최초 판정자·판정 시각을 유지한다.
2. IF 반대 판정 또는 확정된 반려 사유 변경 요청이 오면 THEN 시스템 SHALL 409로 거절하고 기존 판정을 유지한다.
3. WHILE 승인·반려가 동시에 들어오는 동안 THE 시스템 SHALL 요청 행 잠금으로 직렬화해 한 판정만 확정한다.
4. THE 시스템 SHALL 승인·반려를 뒤집는 관리자 예외 API를 제공하지 않는다.

### R6. 재심사와 철회의 잠금 경계
**사용자 스토리:** 창작자로서, 반려 후 다시 심사받거나 판정 전 요청을 철회하고 싶다. 그래야 자료를 고쳐 재신청하거나 요청을 거둘 수 있다.

#### 수용 기준
1. WHEN 반려·승인 후 재심사가 필요하면 THEN 시스템 SHALL Studio가 발급한 새 요청 ID로 새 심사를 생성하고 과거 스냅샷·판정은 유지한다.
2. WHERE 철회 완료 후 동일 자료 재신청 맥락에서 THE 시스템 SHALL 자료를 수정하지 않아도 새 요청 ID로 새 심사를 접수하며 이전 심사를 다시 열거나 철회 기록을 지우지 않는다(결정 59).
3. WHEN `ProjectReviewWithdrawalRequested`를 같은 `review-project` 그룹에서 받으면 THEN 시스템 SHALL 판정과 같은 잠금 경계에서 처리한다.
4. WHERE 심사 검토에 착수한 뒤라도 최종 판정 전 맥락에서 THE 시스템 SHALL 철회를 허용한다(결정 60).
5. WHEN 철회가 먼저 커밋되면 THEN 시스템 SHALL 이후 승인·반려로 바꾸지 않고, 승인·반려가 먼저 커밋되면 이후 철회가 그 판정을 바꾸지 않으며 확정 결과를 안내한다(결정 53).
6. THE 시스템 SHALL 먼저 커밋된 결과를 `ProjectReviewWithdrawalResolved`로 전달한다.
7. WHEN 철회가 원요청보다 먼저 도착하면 THEN 시스템 SHALL 철회 의사를 내구 보관하고 원요청 수신 때 같은 요청 ID로 대조해 확정하며, 원요청 없이 심사 행을 추측해 만들지 않는다.
8. THE 철회 완료 SHALL `WITHDRAWN`이며 반려로 취급하지 않는다. `withdrawnBy`·`withdrawalReason`·`withdrawnAt`을 판정 필드와 별도로 기록하고, 철회된 요청의 승인·반려는 409다.
9. WHEN 같은 철회 명령이 다른 eventId로 재전달되면 THEN 시스템 SHALL 최초 결과로 수렴하고 종료된 요청을 다시 열지 않는다.

### R7. 접근 권한
**사용자 스토리:** 플랫폼으로서, 심사 자료와 판정을 심사 권한자에게만 열고 싶다. 그래야 판정자가 위조되지 않는다.

#### 수용 기준
1. WHERE 모든 업무 API 맥락에서 THE 시스템 SHALL Gateway와 Review 양쪽에서 `REVIEWER` 또는 `ADMIN` 역할을 요구한다.
2. IF 익명 또는 `REVIEWER`·`ADMIN` 없는 `MEMBER`·`CREATOR` 요청이면 THEN 시스템 SHALL 접근을 거절한다(익명 401, 권한 부족 403).
3. THE 판정자 SHALL 요청 본문이 아니라 검증된 JWT `sub`로 정하며 본문으로 위조할 수 없다.
4. WHERE 초기 범위에서 THE 시스템 SHALL 심사자 배정 없이 `REVIEWER`·`ADMIN`이 모든 접수 건을 조회·판정하게 한다.

### R8. 목록·결과 이벤트 계약
**사용자 스토리:** 심사자로서, 접수 건을 상태·프로젝트로 찾고 결과를 Studio에 정확히 전달하고 싶다. 그래야 운영과 창작자 쪽 반영이 맞는다.

#### 수용 기준
1. WHEN `GET /api/v1/reviews` 요청이 오면 THEN 시스템 SHALL 상태·Project 필터와 페이지 조회를 제공한다(기본 page=0, size=20, 최대 size=100, 접수 시각 내림차순과 요청 ID 정렬).
2. THE 시스템 SHALL 새 공통 이벤트를 추가하지 않고 Studio가 사용하는 `ProjectReviewRequested`(수신)·`ProjectReviewApproved`·`ProjectReviewRejected`(발행) 계약을 그대로 연결한다.
3. THE 결과 이벤트의 `reviewRequestId`·`projectId`·`contentVersion`·`productCode` SHALL 저장된 요청·스냅샷에서 가져오고, `actor`는 검증된 JWT `sub`, 반려 `feedback`은 확정한 사유로 한다.
4. THE Kafka 키와 Outbox `aggregateId` SHALL `productCode`로 맞춰 같은 Project의 결과 발행 순서를 유지한다.
5. WHEN 잘못된 입력·미접수 요청·확정 충돌이 발생하면 THEN 시스템 SHALL 각각 400·404·409로 응답한다.

## 범위 밖(후속)

- 자동 심사·다단계 관문·정교한 담당 배정/SLA (구 유형·등급 연동·체크리스트·이의 제기와 함께 제거).
- 심사 목록/상태 조회의 사용성 개선, 진행 중 요청 보류·창작자 통지 계약 — G4·G5 범위.
- 관리자 강제 철회, 종료된 심사의 ZIP 신규 URL 발급 허용 범위(정책 미정. 이미 발급한 URL·받은 파일은 상태 변경으로 회수할 수 없음).
- 승인·반려를 뒤집는 재판정 예외 API.
