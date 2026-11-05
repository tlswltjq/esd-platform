# 설계: Studio

## 아키텍처 위치

- 포트 8085, 저장소 MySQL.
- `core/{domain, port, service}` + `api/{controller, listener}` + `adapter/` + `config` 경계를 따른다.
- 환경 설정 바인딩·조립은 `config`, 외부 연동 계약은 `core/port`, 구현(S3/MinIO 등)은 `adapter/`에 둔다.
- 외부 이벤트는 `api/listener`의 Mapper에서 봉투·본문·키를 검증해 내부 입력으로 변환한다.
- Gateway(8080)가 `/api/v1/studio/**`를 라우팅한다. 소유자는 Auth JWT의 sub로 직접 식별하며 Workspace·sellerId 매핑은 없다.

## 핵심 설계 결정

- **Project 중심 축소.** Studio는 상세정보·대표 빌드·요청 참조만 보관하고 APPROVED·PUBLISHED 상태나 공개본 참조, `/publish` API를 두지 않는다.
- **요청 스냅샷은 불변.** 심사·출시 요청을 발행한 상세정보·파일 스냅샷은 변경하지 않는다. 처리 중에도 상세정보 편집을 허용하며 실제 변경은 contentVersion을 증가시킨다.
- **프로젝트 전체 단일 심사·단일 출시.** 모든 버전을 통틀어 진행 중 심사·출시를 각각 1건으로 제한하고, 철회 접수 중에도 자리를 유지한다. 활성 요청은 작업본 버전과 독립적으로 조회한다.
- **명령 멱등과 기대 버전.** 심사·출시 제출 본문은 requestId·contentVersion이 필수다. 같은 명령은 최초 요청으로 수렴하고, 다른 대상·내용은 충돌로 거절하며, 오래된 재전송이 현재 작업본을 새로 제출하지 않는다.
- **철회는 내구 접수.** 명시적 대상과 선택 reason을 202로 접수하고 PENDING→WITHDRAWN/DENIED 영수증을 제공한다. 접수는 최종 성공을 보장하지 않고 정본 결과를 보존한다.
- **장르는 순위 없는 집합.** 작업본 0~3개, 새 심사 1~3개. 기준 목록은 코드 수정·배포로 관리하고 선택 중지해도 기존 근거는 보존한다.

## 데이터 모델

| 모델 | Studio가 보관하는 내용 |
|---|---|
| Project | ownerSubject(Auth sub), productCode, 상세정보, 대표 빌드, contentVersion, 장르 집합, 요청 참조 |
| Build | 성공한 전체 ZIP의 버전·파일명·경로·크기·SHA-256 |
| ProjectReviewRequest | 심사 요청의 버전·빌드·불변 스냅샷(장르 포함)과 외부 결과·피드백·처리자·수신 시각 |
| ProjectReleaseRequest | 승인된 버전의 출시 요청, 연결된 심사 요청, 요청 이벤트 ID와 외부 결과·사유·수신 시각 |
| 철회 명령/영수증 | 심사·출시 철회의 대상 요청·선택 reason·PENDING/WITHDRAWN/DENIED와 행위자·시각·정본 결과 |

- 활성 심사·출시는 nullable 생성 열의 유일 키로 프로젝트별 1건을 DB에서도 보장한다(Studio V26).
- 장르는 `studio_project_genre`(Studio V27)에 중복 없는 집합으로 저장하고, 페이지 조회에는 컬렉션 배치 로딩을 사용한다.
- 공통 Outbox·Inbox는 네 업무 모델과 별도다. Flyway V1~V24 체크섬을 유지하고 V25~V27에서 전환했다.
- 모델 속성은 `docs/domain/models/studio.md` 참조.

## API 계약

`/api/v1/studio` 아래 업무 엔드포인트다. 공개 썸네일 외에는 CREATOR와 소유권을 확인한다.

| 메서드 | 경로 | 동작 |
|---|---|---|
| GET | `/genres` | CREATOR 장르 기준 목록·선택 가능 여부 |
| POST / GET | `/projects` | Project 생성(201)·내 페이지 목록 |
| GET | `/projects/{id}` | 상세정보·현재 버전의 요청·releaseEligible·활성 요청 |
| PUT | `/projects/{id}/store-page` | contentVersion으로 상세정보 수정 |
| POST / GET | `/projects/{id}/builds` | 전체 ZIP 등록(201)·페이지 이력 |
| POST | `/projects/{id}/thumbnail` | PNG/JPEG 등록(201) |
| GET | `/assets/{projectId}/{assetId}` | 공개 썸네일 조회 |
| POST / GET | `/projects/{id}/review-requests` | 심사 요청·재요청(202, requestId·contentVersion)·페이지 이력 |
| POST / GET | `/projects/{id}/release-requests` | 출시 요청·재요청(202, requestId·contentVersion)·페이지 이력 |
| POST / GET | `/projects/{id}/review-requests/{targetId}/withdrawals` | 심사 철회 내구 접수(202)·영수증 이력 |
| GET | `/projects/{id}/review-requests/{targetId}/withdrawals/{withdrawalId}` | 심사 철회 최종 결과 조회 |
| POST / GET | `/projects/{id}/release-requests/{targetId}/withdrawals` | 출시 철회 내구 접수(202)·영수증 이력 |
| GET | `/projects/{id}/release-requests/{targetId}/withdrawals/{withdrawalId}` | 출시 철회 최종 결과 조회 |

- 네 GET 이력 목록은 기본 page=0·size=20, 최대 100, ID 내림차순, `items/page/size/totalElements/totalPages`다. 잘못된 범위·정수 조회 구간 초과는 400이다. 철회 영수증 조회·상세는 no-store다.
- `/publish`와 심사 판정·실제 출시를 수행하는 HTTP API는 없다.

## 이벤트 계약

| 방향 | 이벤트 | 비고 |
|---|---|---|
| 발행 | `ProjectReviewRequested` | 심사 요청·재요청 시(stove.studio.v1) |
| 발행 | `ProjectReleaseRequested` | 출시 요청·재요청 시 |
| 발행 | `ProjectReviewWithdrawalRequested` | 심사 철회 내구 접수 시 |
| 발행 | `ProjectReleaseWithdrawalRequested` | 출시 철회 내구 접수 시 |
| 수신 | `ProjectReviewApproved/Rejected` | 외부 심사 결과를 요청 ID·버전에 기록 |
| 수신 | `ProjectReleaseCompleted/Rejected` | 외부 출시 결과(stove.release.v1) 기록 |
| 수신 | `ProjectReviewWithdrawalResolved` | 먼저 커밋된 심사 결과(판정 또는 철회) |
| 수신 | `ProjectReleaseWithdrawalResolved` | 먼저 커밋된 출시 결과(공개 또는 철회) |

## 트랜잭션·동시성

- 서비스가 Inbox·Project 잠금·업무 변경·Outbox를 같은 트랜잭션에서 조율한다(멱등 가드는 서비스 소유).
- Project 행 잠금과 활성 요청 DB 유일 키로 버전 독립적 단일 심사·출시를 보장한다. 종료된 요청 ID가 현재 활성 ID와 같을 때만 자리를 해제한다.
- 저장소 HEAD·파일 확인은 짧은 DB 트랜잭션 밖에서 수행하고, 잠금 뒤 소유권·버전·빌드를 재확인한다.
- 같은 명령의 재전송은 과거 영수증을 재현하고, 상충 본문·충돌 이벤트는 조용히 성공 처리하지 않고 계약 오류로 구분한다.

## 외부 연동

- `BuildStorage`는 실제 `S3BuildStorage`로 전체 ZIP·썸네일을 S3/MinIO에 저장한다(`stove.storage.provider=s3`).
- 심사·실제 출시는 Studio가 수행하지 않는다. Review·Catalog가 정본을 처리하고 결과를 이벤트로 돌려준다.
- 검증은 별도 스택에 Auth·Gateway·Studio와 MySQL·Redis·Kafka·MinIO만 실행하고, 외부 결과 픽스처를 Kafka로 보내 요청 결과·재요청·철회·지연 응답을 검증한다.

## 완료 상태

2026-10-14 Project 중심 재작성을 완료했고(커밋 `79cc163`·`37f9807`), 이후 목록 페이지, 장르 분류·AND 검색 연계, 단일 심사/출시와 명시적 철회를 구현·검증했다.
자세한 범위·검증 수치는 `docs/studio-simplification-plan.md`, `docs/studio-list-pagination-plan.md`, `docs/store-genre-filter-plan.md`, `docs/project-review-withdrawal-verification.md`를 따른다.
