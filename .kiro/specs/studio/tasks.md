# 작업: Studio

## 완료 (재작성·검증 기록은 [근거 색인](../../../docs/evidence.md))

- [x] Project 중심 축소(APPROVED/PUBLISHED·공개본 참조·`/publish` 제거), 업로드·요청·외부 결과 기록 — 2026-10-14 (`79cc163`, `37f9807`)
- [x] 목록 페이지(requirements R8) — 2026-11-03
- [x] 장르 선택(requirements R2, V27) — 2026-11-03
- [x] 프로젝트 전체 단일 심사·출시, requestId·contentVersion 명령 멱등, 심사·출시 철회 내구 접수와 결과 수신(requirements R3~R7, V26) — 2026-11-03
- [x] Studio 자료→Catalog 공개→Store 조회를 잇는 G4 전체 운영 여정의 최신 전체 완료 — 2026-11-08, main `8c113393` 실제 9개 앱 12단계 (`verify-g4-operations.py`, [근거 색인](../../../docs/evidence.md) 2026-11-08 G4 행)
- [x] 다수 서비스 동시 기동 시 DB 최초 접속 기한 복원력(DB를 쓰는 9개 앱 공통) — 2026-11-09, 부팅 때만 첫 연결 60초 재시도·실행 중 `connection-timeout` 3초 유지([결정 136](../../../docs/decisions.md#db-startup-wait)). `DatabaseStartupWaitContractTest`(9개 앱 설정·재시도 예산이 Compose health 판정 전에 끝남)·`DatabaseStartupWaitIntegrationTest`(4초 닫힌 DB 포트에서 첫 연결 대기)

## 진행 중 / 남음 (일반화 공백)

- [ ] [결정 필요] 심사 진행 중 요청의 보류 필요성과 창작자 통지 계약 — Review·Catalog 경계와 함께
- [ ] [결정 필요] 새 Studio 검색·상태 필터, 목록 요약/상세 분리 — 필요 시 별도 확장

## 범위 밖(외부 업무·후속)

- 심사 판정·실제 출시·공개 상품/상점 진열·파일 배포(Review·Catalog·Store).
- 예약·출시 채널·승격·롤백, CI·테스터·웹훅, 분할 업로드 세션.
- 공개 확정 전 철회의 시간 보장(필요하면 예약 시각·유예·별도 최종 공개 행동 중 선택)·공개 후 되돌리기.
- 대표 장르·선택 순위·장르 계층, 동일 자료 차단용 내용 해시·고비용 검증 캐시.
- DLC 모델(별도 권한이 필요하면 독립 상품·Project와 본편 관계를 추천했으나 미결정)·선물하기·구매와 사용권 등록 분리.
