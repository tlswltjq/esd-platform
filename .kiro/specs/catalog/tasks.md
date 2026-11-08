# 작업: Catalog

## 완료 (검증 수치는 [근거 색인](../../../docs/evidence.md))

- [x] 출시 요청 처리: 입력 계약 검증·최초 공개·상위 버전 교체·하위 버전 거절(STALE_CONTENT_VERSION), 불변 스냅샷 — 2026-10-15
- [x] 중복·동시성: Inbox 중복 제거, 동일 요청 재전달 결과 유지, 식별 충돌·상충 스냅샷 DLT, 행 잠금·유일 제약·전체 재시도 — 2026-10-15
- [x] 공개 조회: `GET /api/v1/products`(page≤100·안정 정렬), `by-code/{code}` 상세, 익명 GET, 내부 필드 미노출·400/404 — 2026-10-15
- [x] 이벤트: ProjectReleaseRequested 수신·Completed/Rejected 발행, requestEventId 연결, consumer group `catalog-project-release`(earliest) — 2026-10-15
- [x] V11 catalog_product·catalog_release·인덱스 추가, V1~V10 체크섬 유지, 구 업무 코드(프로모션·견적·캐시·재색인·Workspace 연동) 제거 — 2026-10-15
- [x] MVP 독립 검증: 실제 5개 앱 로그인·업로드·심사·출시·공개 조회·Studio 완료 반영, 릴레이 중단·재시작·Kafka 복구 (관련 481건) — 2026-10-15
- [x] 판매 상태: 판매자 ON_SALE/STOPPED·ADMIN 독립 제한·BLOCKED, catalog_sale_change 감사, V12 이행, 공개 사본 saleStatus·catalogVersion, Order 새 주문/승인 접수 경계 (관련 1,730건) — 2026-11-02
- [x] 다운로드 제한: ADMIN GET/PUT·202 접수·필수 사유, catalog_download_change, V13 이행, 저장소 태그 PENDING/APPLIED/FAILED 수렴, 읽기 계정 IAM 거절 검증 (관련 348건) — 2026-11-02
- [x] 검색·목록 노출: listingPreference(AUTO/LISTED/UNLISTED)·platformListingHidden, 최종 listed 규칙, catalog_listing_change, V14 이행, 공개 사본 listed·내부 전체 동기화 키 (관련 548건) — 2026-11-02
- [x] 공개 상세 B: 최종 listed=false의 공개 상세·새 주문 404, ProductPublicAccess 일원화, 내부 order-products 근거(publicDetailAllowed), 전용 Order 서비스 키 (관련 768건) — 2026-11-02
- [x] 출시 철회 조정과 내구 공개 작업(V15·자동 재시도·ADMIN 작업 조회): ProjectReleaseWithdrawalRequested 수신·Resolved 발행, 공개 확정과 같은 종료 경계, 선확정 결과 유지, 유예시간 없음 — 2026-11-03 (requirements R9·R10)
- [x] genres(1~3개) 공개 응답·공개 이벤트·내부 정본 포함, 복수 장르 AND 검색 연계 — 2026-11-03 (G4 장르 분류)

## 진행 중 / 남음 (일반화 공백)

- [ ] [결정 필요] 공개본 변경 이력의 조회 계약·사용성 — G4 ([일반화 계획](https://github.com/tlswltjq/esd-platform/blob/0cc225bf/docs/platform-generalization-plan.md) Catalog 행 "공개본 변경 이력")
- [ ] [결정 필요] 허용 지연 SLA와 실제 운영 경보·화면 — G4
- [ ] [외부 대기] 공유 환경의 정책/계정 준비와 실제 배포 — G4 이후
- [ ] G4 전체 완료 판정(실제 운영 여정 전체) — G4

## 범위 밖(후속)

- 예약 출시·롤백·채널·관리자 강제 공개.
- 할인·프로모션([Q13](../../../docs/domain/open-questions.md))·상품군·번들·등급·주문 견적·검색 색인/재색인/캐시.
- 독립 공개 접근 C의 독립 플래그·관리 API.
- 이미 시작한 전송 종료·다운로드 파일 회수·URL 영구 폐기.
