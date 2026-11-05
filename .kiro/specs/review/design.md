# 설계: Review

## 아키텍처 위치

- 저장소 MySQL. 외부 저장소는 읽기 전용 S3/MinIO.
- `core/{domain, port, service}` + `api/{controller, listener}` + `adapter/` + `config` 경계를 따른다.
- 외부 이벤트 전체는 `api/listener`의 Mapper에서 검증·내부 입력으로 변환하고, 조립은 `config`가 담당한다.
- Gateway(8080)가 기존 `/api/v1/reviews/**` 경로를 라우팅하고 `REVIEWER`·`ADMIN` 접근을 제어한다.
- Studio가 상세정보·전체 ZIP·심사 요청/재요청을 관리하고, Review는 접수·스냅샷 열람·수동 판정·결과 발행만 담당한다. Review는 Studio의 작업본을 수정하거나 요청을 대신 생성하지 않는다.

## 핵심 설계 결정

- **요청 단위 단일 모델.** 두 심사 흐름·여섯 유형·감사/판정 이력 엔티티를 요청 단위 `ProjectReview` 하나로 통합하고 상태를 `REQUESTED / APPROVED / REJECTED / WITHDRAWN`으로 제한한다.
- **불변 스냅샷.** 요청 당시의 `ProjectSnapshot` 전체를 불변 JSON으로 보관하고, Studio의 최신 Project로 갱신하지 않는다. 한 요청의 최종 판정은 한 번이다.
- **재심사는 새 요청.** 반려·승인·철회 후 재심사는 Studio가 발급한 새 요청 ID로 생성하고 과거 스냅샷·판정을 유지한다.
- **먼저 커밋된 결과 유지(결정 53).** 같은 요청의 승인·반려·철회가 겹치면 Review의 트랜잭션 커밋을 기준으로 먼저 확정된 결과를 유지한다. 버튼 클릭·Studio 접수·행 잠금 획득 시각이 기준이 아니다.
- **판정자는 서버 근거.** `actor`는 검증된 JWT `sub`로 기록하고 요청 본문으로 받지 않는다.
- **읽기 전용 열람.** 심사자 빌드 열람은 스냅샷의 `storagePath`를 쓰는 읽기 전용 S3/MinIO 어댑터로 짧은 수명의 GET URL을 발급한다.

## 데이터 모델

업무 엔티티는 `ProjectReview` 하나이며 새 테이블 `project_review`를 쓴다. 공통 Outbox·Inbox와 구분한다.

| 값 | 의미 |
|---|---|
| `reviewRequestId` | Studio 발급 요청 ID. PK로 그대로 사용 |
| `requestEventId` | 최초 접수한 요청 이벤트 ID |
| `projectId, productCode, ownerSubject, contentVersion` | 스냅샷에서 추출한 조회·결과 연결 정보 |
| `snapshot` | 받은 `ProjectSnapshot` 전체(불변 JSON) |
| `status` | `REQUESTED` · `APPROVED` · `REJECTED` · `WITHDRAWN` |
| `feedback` | 반려 사유. 반려 시 필수, 최대 2000자 |
| `actor` | 최초 최종 판정을 수행한 JWT `sub` |
| `decidedAt` | 최초 최종 판정 시각 |
| `requestedAt` | 요청 이벤트의 발생 시각 |
| `createdAt, updatedAt` | 수신·DB 변경 시각 |

- `reviewRequestId`에 유일성을 보장하고, 상태·접수 시각과 Project·접수 시각에 조회 인덱스를 둔다.
- Project·버전·빌드 조합에는 유니크 제약을 두지 않는다. 동일 버전의 반려/철회 후 재요청도 새 ID로 접수된다.
- 다른 서비스 테이블을 조인하거나 외부 DB FK를 만들지 않는다.
- 제출 스냅샷·심사자 응답의 `genres`에는 알려진 코드 1~3개가 포함되며, 이미 제출한 장르가 나중에 선택 중지돼도 기존 심사는 이어간다.

모델 세부는 `docs/domain/models/review.md` 참조.

## API 계약

모든 업무 API는 Gateway 경로 `/api/v1/reviews/**` 안에 두고, Gateway와 Review 양쪽에서 `REVIEWER` 또는 `ADMIN`을 요구한다. 총 5개 업무 엔드포인트다.

| 메서드 | 경로 | 역할 | 동작 |
|---|---|---|---|
| GET | `/api/v1/reviews` | REVIEWER·ADMIN | 상태·Project 필터와 페이지 조회(page=0, size=20, 최대 100, 접수 시각 내림차순·요청 ID 정렬) |
| GET | `/api/v1/reviews/{reviewRequestId}` | REVIEWER·ADMIN | 제출 상세정보·빌드 버전·크기·체크섬·판정 조회(내부 경로 미노출) |
| GET | `/api/v1/reviews/{reviewRequestId}/build-download` | REVIEWER·ADMIN | 해당 스냅샷 ZIP의 읽기 전용 GET URL 발급(기본 5분) |
| POST | `/api/v1/reviews/{reviewRequestId}/approve` | REVIEWER·ADMIN | 승인 |
| POST | `/api/v1/reviews/{reviewRequestId}/reject` | REVIEWER·ADMIN | 필수 `feedback`으로 반려 |

- 응답은 공통 `ApiResponse`와 전용 DTO를 사용하고 내부 저장 경로를 노출하지 않는다.
- 판정 대상은 경로의 `reviewRequestId`로 지정한다. `actor`·판정 시각·`projectId`·`buildId`·`contentVersion`은 본문으로 받지 않는다.
- 응답 코드: 판정 성공 200(Studio 반영 완료를 뜻하지 않음), 잘못된 입력 400, 인증 실패 401, 권한 부족 403, 미접수 요청 404, 확정 판정 충돌 409.
- BFF 쿠키를 사용하는 POST는 기존 Gateway의 CSRF 계약을 따른다.
- 별도 접수·재접수·이의 제기·판정 수정 API는 만들지 않는다.

## 이벤트 계약

새 공통 이벤트를 추가하지 않고 완료된 Studio가 사용하는 계약을 연결한다. 리스너와 접수 유스케이스는 새 consumer group `review-project`를 공유한다.

| 방향 | 이벤트 | 토픽 |
|---|---|---|
| 수신 | `ProjectReviewRequested` | `stove.studio.v1` |
| 수신 | `ProjectReviewWithdrawalRequested` | `stove.studio.v1` |
| 승인 결과 | `ProjectReviewApproved` | `stove.review.v1` |
| 반려 결과 | `ProjectReviewRejected` | `stove.review.v1` |
| 철회 결과 | `ProjectReviewWithdrawalResolved` | `stove.review.v1` |

- 결과의 `reviewRequestId`·`projectId`·`contentVersion`·`productCode`는 저장된 요청·스냅샷에서, `actor`는 검증된 JWT `sub`, 반려 `feedback`은 확정 사유에서 가져온다.
- 결과 이벤트의 UUID·시각은 기존 `of()` 팩터리가 생성하고 Kafka 키는 `productCode`를 유지한다. Outbox `aggregateId`도 `productCode`로 맞춘다.
- 수신 경계는 입력 계약만 확인하며 Studio의 소유권·출시 조건을 다시 판정하지 않는다. 철회는 판정과 같은 잠금 경계에서 처리한다.
- Studio는 결과를 원래 요청 ID·버전에 기록하고 현재 버전의 출시 요청 조건을 계산한다. Review는 Studio DB를 직접 바꾸거나 HTTP로 동기 반영하지 않는다.

## 외부 연동

| 포트 | 어댑터 | 비고 |
|---|---|---|
| `ReviewBuildStorage` (core.port) | `S3ReviewBuildStorage` (adapter) | 읽기 전용 S3/MinIO 계정 |

- Review는 스냅샷의 s3 경로와 설정된 버킷을 확인하고 객체 존재·크기를 확인한 뒤 GET URL과 `expiresAt`·`fileSize`·`checksum`을 반환한다.
- 저장소에는 읽기·조회 권한만 부여하며 업로드·삭제·버킷 생성을 수행하지 않는다. 서명 URL은 캐시하지 않고 로그에 남기지 않는다.
- Studio와 같은 버킷·리전·암호화 조건을 쓰되 URL의 외부 접근 주소는 별도 설정할 수 있다. Studio의 구현 클래스·파일 객체를 직접 참조·복제하지 않는다.
- HEAD만으로 해시 검증 완료를 주장하지 않으며, ZIP 실행·자동 품질 검사·등급 판정을 하지 않는다.

## 트랜잭션·동시성 동작 요약

- Inbox 마킹과 심사 접수를 하나의 DB 트랜잭션으로 묶는다. 동일 이벤트 재수신은 Inbox로 중복 제거한다.
- 같은 요청 ID에 다른 스냅샷이 오면 계약 위반으로 처리하고 기존 자료를 덮어쓰지 않는다. 다른 이벤트 ID로 동일 스냅샷이 오면 기존 심사를 유지한다.
- 판정은 요청 행 잠금·상태 변경·결과 Outbox를 하나의 DB 트랜잭션으로 묶는다. 커밋 뒤 Outbox 릴레이가 결과를 발행하고 실패·재전달은 공통 모듈이 처리한다.
- 동시 승인·반려는 행 잠금으로 한 판정만 확정한다. 동일 판정·사유 재시도는 최초 결과를 반환하고 새 이벤트를 만들지 않는다. 확정 판정·반려 사유 변경은 409다.
- 저장소 조회·서명은 긴 DB 트랜잭션 밖에서 실행한다. 파일 열람 실패·저장소 일시 장애를 판정으로 자동 변환하지 않는다.
- 전환: 기존 Flyway V1~V9와 역사 자료는 보존하고 V10의 `project_review`만 새 업무에 사용한다. 구 데이터는 자동 이관하거나 과거 승인을 새 요청에 연결하지 않는다.
