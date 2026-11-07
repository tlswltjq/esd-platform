# 작업: Store

## 완료 (검증 수치는 [근거 색인](../../../docs/evidence.md))

- [x] StoreProduct 사본·버전 규칙·제목 검색/정렬/페이지·상세·`CatalogProductPublished` 수신·시작 시 초기 동기화 — 2026-10-15
- [x] 자동 초기화 재시도·주기 정본 대조·동일 버전 정본 교정(A)·`store_reconciliation` 원자 감사·`stove.store.sync.*` 지표 — 2026-11-03
- [x] 장르 분류(플랫폼 10개)·`store_product_genre`·복수 장르 AND 검색·`/storefront/genres` — 2026-11-03
- [x] 자동 복구 경보 연결: `/actuator/prometheus` 익명 GET 허용, `StoreSyncInitStalled`(초기화 10분)·`StoreSyncReconcileFailing`(대조 성공 5분 끊김) 경보, 격리 스택 발화·해제 검증 — 2026-11-05, [결정 85](../../../docs/decisions.md), PR #104
- [x] 서비스 11개 공통 Prometheus 수집에 Store 포함(실제 격리 Prometheus 대상 up) — 2026-11-06, PR #113

## 진행 중 / 남음 (일반화 공백)

- (Store 단독 남은 일반화 항목 없음.) 계정 복구·상태 등 G5 공통 운영은 [Auth](../auth/tasks.md)·[Gateway](../gateway/tasks.md) 작업과 [로드맵](../../../docs/roadmap.md)을 따른다.

## 범위 밖(후속)

- Elasticsearch·개인화·대규모 무중단 재색인.
- 가격 범위·판매 상태 등 추가 필터, 대표 장르·장르 계층.
- 전역 단일 스캔·내구 커서/임대·다중 인스턴스 조율.
- 관리자 수동 재동기화 API를 정상 운영 경로로 두는 것(결정 66).
