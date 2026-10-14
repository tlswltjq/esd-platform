# Studio 축소·단순화 계획

[문서 목록](README.md) · [모델](domain/models/studio.md) · [실행·검증](studio-guide.md)

## 책임과 작업 기준

Studio는 출시를 준비하는 창작자의 작업 도구다. 책임은 출시용 상세정보·빌드 관리,
출시 조건을 확인하기 위한 심사 요청·재요청, 출시 요청·재요청이다.
심사 판정과 실제 출시는 외부 업무이며 Studio에서 수행하지 않는다.
외부 결과는 요청 이력과 현재 상세정보 버전의 출시 요청 가능 여부를 표시하기 위해 기록한다.

Studio는 2026-10-14 재작성을 완료했다. 현재 완료된 기준 모듈은 Auth·Gateway·Studio·Review다.
미완료 모듈의 API·DB·판매자·공개 상품 구조는
Studio의 설계와 검증 제약으로 삼지 않는다. 기준은 [AGENTS.md](../AGENTS.md)를 따른다.

## 사용자 흐름

1. Auth에서 CREATOR로 가입·로그인하고 Gateway를 통해 Project를 생성한다.
2. 전체 ZIP과 썸네일을 업로드하고 제목·소개·영상 URL·내용·KRW 가격을 관리한다.
3. 현재 상세정보·빌드 버전의 스냅샷으로 심사 요청을 발행한다.
4. 외부 승인·반려 결과를 해당 요청 기록에 저장한다. 반려 뒤 재요청은 새 기록을 만든다.
5. 현재 상세정보 버전의 외부 승인 결과가 있으면 출시 요청을 발행한다.
6. 외부 출시 업무가 보고한 완료·거절 결과를 해당 요청에 기록한다.
7. 거절 뒤 다시 요청하거나, 상세정보·빌드를 변경하고 새 심사·출시 요청을 보낸다.

Project 자체에는 APPROVED·PUBLISHED 상태나 공개본 참조를 두지 않는다.
실제 상품 공개·상점 진열·파일 배포와 공개본 관리는 Studio의 책임 밖이다.

## 최소 모델

| 모델 | Studio가 보관하는 내용 |
|---|---|
| Project | Auth sub인 ownerSubject, productCode, 상세정보, 대표 빌드, contentVersion과 요청 참조 |
| Build | 성공한 전체 ZIP의 버전·파일명·경로·크기·SHA-256 |
| ProjectReviewRequest | 발행한 심사 요청의 버전·빌드·불변 스냅샷과 외부 결과·피드백·처리자·수신 시각 |
| ProjectReleaseRequest | 승인된 버전의 출시 요청, 연결된 심사 요청, 요청 이벤트 ID와 외부 결과·사유·수신 시각 |

Workspace와 별도 sellerId 매핑은 제거한다. 소유자는 Auth JWT의 sub로 직접 식별한다.
공통 Outbox·Inbox는 네 업무 모델과 별도다.

## 요청 상태와 재요청

심사 요청 기록은 REQUESTED, APPROVED, REJECTED를 표시한다. 승인·반려는 외부에서 보고한 결과다.
출시 요청 기록은 REQUESTED, COMPLETED, REJECTED를 표시한다. COMPLETED도 외부 결과 수신 기록이다.
이 상태를 Project의 심사·출시 상태나 Studio의 실행 완료로 사용하지 않는다.

- 요청을 발행한 상세정보·파일 스냅샷은 변경하지 않는다.
- 요청 처리 중에도 상세정보를 편집할 수 있다. 실제 변경은 contentVersion을 증가시킨다.
- 다른 버전의 승인 결과는 현재 버전의 출시 요청 조건을 충족하지 않는다.
- 현재 요청·버전의 처리 중 요청을 반복하면 같은 요청을 반환한다. 완료된 동일 요청도 중복 발행하지 않는다.
- 거절 뒤 심사·출시 재요청은 새 ID와 새 요청 이벤트로 발행하며 이전 이력을 유지한다.
- 요청 ID·Project·버전·출시 요청 이벤트 ID가 맞는 외부 결과만 기록한다.
- 처리된 요청의 뒤늦은 상충 결과와 중복 이벤트는 기존 기록·새 요청을 덮어쓰지 않는다.
- 상세정보 수정 후 도착한 과거 결과는 과거 요청에만 기록한다.

Project 조회의 currentReviewRequest와 currentReleaseRequest는 현재 상세정보 버전의 요청이다.
releaseEligible은 현재 버전의 외부 심사 승인 결과로부터 계산하는 출시 요청 가능 여부다.
과거 요청은 별도의 심사·출시 요청 이력 API에서 조회한다.

## 업로드와 상세정보

상세정보는 제목, 짧은 소개, 썸네일, 선택적인 외부 HTTP/HTTPS 영상 URL, 내용과 KRW 정수 가격이다.
전체 빌드는 ZIP 한 개이며 기본 1GiB, PNG/JPEG 썸네일은 기본 5MiB·8192픽셀을 상한으로 둔다.
파일은 임시 디스크에서 검사·해시 계산 후 고유 S3/MinIO 객체로 저장한다.
짧은 DB 트랜잭션에서 소유권·버전을 재확인하고 빌드 등록과 대표 빌드 변경을 함께 저장한다.
DB 실패 시 새 객체를 정리한다. 요청에 첨부한 기존 파일을 새 업로드로 덮어쓰거나 삭제하지 않는다.

CI·OIDC 신뢰 정책, 테스터, 웹훅, 다중 자료 판본, 여섯 심사 관문, 출시 채널·예약·승격·롤백,
플랫폼별 빌드 묶음·델타·업로드 세션·자동 보관 정리는 제거한다.

## Studio API

/api/v1/studio 아래 12개 업무 엔드포인트다. 공개 썸네일 외에는 CREATOR와 소유권을 확인한다.

| 메서드 | 경로 | 역할 |
|---|---|---|
| POST / GET | /projects | Project 생성·내 목록 |
| GET | /projects/{id} | 상세정보·현재 버전의 요청·출시 요청 가능 여부 |
| PUT | /projects/{id}/store-page | 상세정보 관리 |
| POST / GET | /projects/{id}/builds | 전체 빌드 등록·이력 |
| POST | /projects/{id}/thumbnail | 썸네일 등록 |
| GET | /assets/{projectId}/{assetId} | 공개 썸네일 조회 |
| POST / GET | /projects/{id}/review-requests | 심사 요청·재요청·이력 |
| POST / GET | /projects/{id}/release-requests | 출시 요청·재요청·이력 |

생성·업로드는 201, 요청 발행 접수는 202다. /publish와 심사 판정·실제 출시를 수행하는 HTTP API는 없다.

## 이벤트와 검증 경계

Studio는 ProjectReviewRequested와 ProjectReleaseRequested를 Outbox로 발행한다.
외부 ProjectReviewApproved/Rejected, ProjectReleaseCompleted/Rejected를 Inbox로 수신해 요청 결과를 기록한다.
출시 결과 토픽은 stove.release.v1이며 기존 미완료 상품 서비스의 토픽·공개 모델에 의존하지 않는다.

검증은 Auth·Gateway의 실제 계약, Studio 자료·요청 관리와 사용하는 공통 이벤트 계약을 기준으로 한다.
별도 스택에는 Auth·Gateway·Studio와 MySQL·Redis·Kafka·MinIO만 실행한다.
심사와 실제 출시를 대신 구현하지 않고, 외부 결과 픽스처를 Kafka로 보내 요청 결과·재요청·지연 응답을 검증한다.

## 저장 구조 전환

Flyway V1~V24의 체크섬을 유지하고 V25에서 심사·공개 테이블을 요청 테이블로 전환한다.
Project의 승인·출시 상태와 공개본 참조, 외부 상품 ID를 제거한다.
기존 스냅샷·요청 결과·파일 이력을 보존하며 현재 모델에서 요청 기록으로만 읽는다.

## 재작성 완료 기록

2026-10-14, 위 범위의 Studio 재작성을 완료했다. 완료는 출시 자료·빌드 관리와
심사·출시 요청·재요청 및 외부 결과 기록을 대상으로 한다.
실제 심사 판정, 상품 공개·상점 진열·파일 배포는 외부 업무로 남는다.

- `79cc163`: Project 중심으로 축소하고 요청 관리와 외부 심사·출시 책임을 분리했다.
- `37f9807`: 스냅샷 변환과 불변 객체 복사를 정리했다.

최종 리팩터링에서 Auth·Gateway·Studio의 기존 단위·통합 테스트를 다시 실행했다.
151건 모두 통과했으며 실패와 건너뜀은 없다.

| 모듈 | 단위 테스트 | 통합 테스트 |
|---|---:|---:|
| Auth | 3 | 8 |
| Gateway | 87 | 1 |
| Studio | 46 | 6 |

단위 검증에는 역할·소유권·버전·요청 상태와 아키텍처 규칙을 포함한다.
통합 검증에는 Auth 토큰 회전·재사용 차단, Gateway 로그아웃,
Studio의 파일·스냅샷·재요청·지연 결과·DB 이관·OpenAPI 계약을 포함한다.
미완료 서비스의 구현을 연결하거나 검증 기준으로 사용하지 않았다.
