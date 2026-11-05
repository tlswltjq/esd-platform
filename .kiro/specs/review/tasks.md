# 작업: Review

## 완료 (MVP·검증 기록 있음)

- [x] 요청 단위 `ProjectReview` 모델·새 테이블 V10, 불변 스냅샷, `ProjectReviewRequested` 수신(새 그룹 `review-project`)·Inbox 중복 제거 — 2026-10-14
- [x] 상태 `REQUESTED / APPROVED / REJECTED` 전이, 수동 승인·반려(필수 feedback 최대 2000자) — 2026-10-14
- [x] 역할·페이지·상태/Project 필터 조회, 상세 DTO(내부 경로 미노출), 5개 업무 API — 2026-10-14
- [x] 읽기 전용 S3/MinIO 빌드 열람 URL(기본 5분·expiresAt·fileSize·checksum), 객체 누락·크기 불일치 처리 — 2026-10-14, `S3ReviewBuildStorage`
- [x] 행 잠금 직렬화, 동일 판정 재시도 멱등, 확정 판정·반려 사유 변경 409 — 2026-10-14
- [x] `ProjectReviewApproved/Rejected` 발행(저장 요청·스냅샷 기준, actor=JWT sub, 키·aggregateId=productCode) — 2026-10-14
- [x] REVIEWER·ADMIN 권한(익명 401·권한 부족 403), 본문 판정자 위조 불가 — 2026-10-14
- [x] 구 심사 흐름·여섯 유형·등급 연동·배정·체크리스트·SLA·이의 제기 제거, V1~V9·역사 자료 보존 — 2026-10-14
- [x] 통합 검증: 실제 MySQL·Kafka·MinIO 중복 접수·Inbox 롤백·판정 원자성·동시 판정·빌드 열람·만료·이관·OpenAPI (단위·아키텍처 50, 통합 12) — 2026-10-14
- [x] 네 모듈 E2E 10건: PKCE 로그인·역할·요청·ZIP·반려·재심사·승인·출시 요청·편집·지연 결과 — 2026-10-14
- [x] 프로세스 재시작 전후 저장 판정·미발행 Outbox 전달·중복 판정 방지 2건, 읽기 전용 계정 업로드·삭제 거절 확인 — 2026-10-14

## 완료 (철회·단일 심사 확장)

- [x] `WITHDRAWN` 상태·감사 필드, 원요청 전에 도착한 철회 보존, 요청별 잠금에서 판정·철회 최종 결과·결과 Outbox — 2026-11-03, `docs/project-review-withdrawal-verification.md`
- [x] 검토 착수 후에도 최종 판정 전 철회 허용, 먼저 커밋된 결과를 `ProjectReviewWithdrawalResolved`로 전달(결정 53·60) — 2026-11-03
- [x] 철회 완료 후 동일 자료 재신청 허용(새 요청 ID, 결정 59), 프로젝트별 진행 중 심사 1건 제약 — 2026-11-03
- [x] 모듈별 최종 검사 695건·실제 역할별 여정 3건 통과(Review 모듈 test 69·integrationTest 20 포함) — 2026-11-03

## 남음 (일반화 공백)

- [ ] 심사 목록/상태 조회의 사용성 개선 — G4 (platform-generalization-plan 4절 Review 행)
- [ ] 진행 중 요청 철회/보류 시 창작자 통지 계약 — G4

## 범위 밖(후속)

- 자동 심사·다단계 관문.
- 정교한 담당 배정/SLA.
- 승인·반려를 뒤집는 재판정 예외 API.
