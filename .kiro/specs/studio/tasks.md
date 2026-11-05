# 작업: Studio

## 완료 (재작성·검증 기록 있음)

- [x] Project 중심 축소, 상세정보·대표 빌드·요청 참조만 보관, APPROVED/PUBLISHED·공개본 참조·`/publish` 제거 — 2026-10-14 (`79cc163`, `37f9807`)
- [x] 전체 ZIP·썸네일 업로드, 임시 디스크 검사·SHA-256·고유 S3/MinIO 객체 저장, DB 실패 시 정리 — `docs/studio-simplification-plan.md`
- [x] 심사 요청·재요청·이력, 출시 요청·재요청·이력, 외부 결과 기록과 releaseEligible 계산 — 2026-10-14
- [x] `ProjectReviewRequested`·`ProjectReleaseRequested` 발행, 외부 결과 Inbox 수신(지연·중복·상충 처리) — 2026-10-14
- [x] 프로젝트·빌드·심사/출시 이력 목록 페이지(기본 20/최대 100, ID 내림차순, `items/page/size/totalElements/totalPages`), 잘못된 범위 400 — 2026-11-03, `docs/studio-list-pagination-plan.md`
- [x] 장르 작업본 0~3개·새 심사 1~3개, `studio_project_genre`(V27), `GET /genres`, 선택 중지 시 기존 근거 보존, 장르만 수정 시 새 버전 심사/출시 — 2026-11-03, `docs/store-genre-filter-plan.md`
- [x] 프로젝트 전체 단일 심사·단일 출시(버전 독립 활성 요청·DB 유일 키·Project 잠금), 이전 출시 중 다음 버전 편집·심사 허용 — 2026-11-03
- [x] 심사·출시 제출의 requestId·contentVersion 필수, 명령 멱등·충돌 거절·오래된 재전송 보호 — 2026-11-03
- [x] 심사·출시 철회 내구 접수(202)·no-store 영수증(PENDING→WITHDRAWN/DENIED)·소유권/대상 검증, 동일 자료 재심사·같은 승인본 재출시 — 2026-11-03, `docs/project-review-withdrawal-verification.md`
- [x] `ProjectReviewWithdrawalRequested`·`ProjectReleaseWithdrawalRequested` 발행, 각 `WithdrawalResolved` 수신 — 2026-11-03
- [x] 통합 검증: OCI 격리 자원에서 모듈별 695건과 실제 Auth/Gateway 역할별 여정·Catalog 재시작 3건 — 2026-11-03

## 진행 중 / 남음 (일반화 공백)

- [ ] Studio 자료→Catalog 공개→Store 조회를 잇는 G4 전체 운영 여정의 최신 전체 완료 — `docs/platform-generalization-plan.md` G4
- [ ] 심사 진행 중 요청의 보류 필요성과 창작자 통지 계약 — Review·Catalog 경계와 함께
- [ ] 새 Studio 검색·상태 필터, 목록 요약/상세 분리 — 필요 시 별도 확장

## 범위 밖(외부 업무·후속)

- 심사 판정·실제 출시·공개 상품/상점 진열·파일 배포(Review·Catalog·Store).
- 예약·출시 채널·승격·롤백, CI·테스터·웹훅, 분할 업로드 세션.
- 공개 확정 전 철회의 시간 보장·공개 후 되돌리기.
- 대표 장르·선택 순위·장르 계층, 동일 자료 차단용 내용 해시·고비용 검증 캐시.
- DLC 모델·선물하기·구매와 사용권 등록 분리.
