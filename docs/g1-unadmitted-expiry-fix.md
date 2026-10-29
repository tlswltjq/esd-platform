# G1 승인 전 검증 보류의 만료 반영 보완

2026-10-30. [실제 TEST 검증](g1-portone-live-verification.md)에 남은 초기 진단 주문을 조사했다. 관리자 종결과 자동 종결 중 새 정책을 먼저 선택할 문제가 아니라, 이미 합의한 **승인 진행 없이 안전하게 만료된 주문의 종료** 계약에서 빠진 경로다. 이 보완을 모든 REVIEW_REQUIRED의 해제·재승인·금융 구제 정책으로 확대하지 않는다.

## 확인한 사실과 원인

- 진단 주문 `0c33eb85-37f4-4038-87e8-9a884484abd8`, 결제 `72f99c18-33d1-4dca-b0f2-892edb8034b8`을 대조했다.
- 보존된 마지막 API 기록에서 Order는 최초 기한 `2026-10-29T14:57:10.302368Z` 뒤 `14:57:10.728815Z`에 EXPIRED였다. Payment만 PENDING에 남았다. 과거 복구 중간 기록의 Order PENDING을 최종 상태로 사용하지 않는다.
- 최종 승인 작업 기록은 REVIEW_REQUIRED·PG_INVALID_EVIDENCE이며 admitted_at·first_prepared_at은 모두 NULL이다. 원래 검증 프록시의 이 결제 confirm POST는 0회다.
- 2026-10-30 05:28 KST의 실제 PortOne GET은 READY·paidAt 없음·취소액 0이었다. amount.paid=9000은 승인 성공의 증거로 사용하지 않는다. 이 조회는 새 승인·취소·환불을 실행하지 않았다.
- REQUESTED는 Order 만료 근거로 종료하지만, 승인 전 검증 오류로 REVIEW_REQUIRED가 된 작업은 이후 정상 READY를 확인해도 계속 보류하는 분기가 원인이다. ApplyOrderExpiry가 진행 중인 작업을 임의 종료하지 않고 작업자에게 맡기는 기존 경계는 유지한다.

## 수정 범위

작업자가 원구매·금액·채널·시도를 대조한 **새 READY 관측**을 얻고, 해당 Order의 **확정된 만료 통지**도 있을 때만 아래 조건을 다시 검사한다.

- 현재 작업이 PG_INVALID_EVIDENCE로 송신 중단된 REVIEW_REQUIRED다.
- 승인 접수·송신 준비·실패 종료 진행 기록이 없다.
- 같은 Payment 잠금과 현재 lease 안에서 Payment가 아직 PENDING이며 Order 만료 근거가 유지된다.

이때 승인 작업과 Payment를 같은 트랜잭션에서 EXPIRED로 바꾸고 토큰 암호문과 조회 예약을 제거한다. 원구매·보류 사유·명령·시도·만료 근거는 보존한다. 새 승인·금융 실패·환불·권한 변경 이벤트는 만들지 않는다. 고객의 기존 결제·승인 진행 조회에 EXPIRED가 반영되며 새 주문은 기존 Order 계약을 따른다.

단순한 시각 경과, 공급자 조회 장애, 여전한 구매/금액 불일치, PAY_PENDING/PENDING, 승인 접수 이력이 있는 작업은 이 경로로 닫지 않는다. 이미 관측한 PAID_WITHOUT_ADMISSION은 이후의 검증 오류가 사유를 덮지 못하게 보존한다. 일반 통신 장애의 자동 복구, 30분 승인 접수 기한과 최초 준비부터 3시간의 송신 창은 바꾸지 않는다. API·스키마 변경은 없다.

## TDD와 검증

OCI 격리 경로는 `sng:/tmp/esd-g1-review-expiry-20261030.dHRXrDSk/work`, 로컬 원자료는 `runs/g1-review-expiry-z4e2zyba/`다. 코드·문서는 로컬에서 편집하고 빌드·테스트는 OCI에서만 실행했다.

- 도메인 Red 1건으로 금융 이상 사유 덮어쓰기를 확인했다.
- 집중 통합에서 정상 재조회 뒤에도 보류가 끝나지 않는 문제와 이상 사유 덮어쓰기의 Red 2건을 확인했다.
- 롤백 테스트의 최초 트리거 준비는 MySQL 권한 오류 1419로 실패했다. 이를 업무 Red로 세지 않았다. 격리 테이블의 임시 CHECK로 바꾸고 제거한 뒤에도 종료되지 않는 실제 Red 1건을 별도로 확인했다.
- 수정 후 Payment 단위·아키텍처 356건과 집중 승인 통합 28건이 통과했다. 이 수치를 아래 최종 전체 결과에 중복 합산하지 않는다.

최종 명령은 OpenAPI 갱신 옵션 없이 OCI에서 실행했다.

```sh
./gradlew --offline --max-workers=2 -Pstove.infraTest.maxParallel=1 \
  :apps:payment:check :apps:payment:bootJar --console=plain
```

| 최종 태스크 | 건수 | 실패·오류·건너뜀 |
|---|---:|---:|
| test | 356 | 0 |
| integrationTest | 240 | 0 |
| migrationTest | 8 | 0 |
| 합계 | 604 | 0 |

최종 게이트는 359.56초에 성공했다. 단위 태스크는 같은 소스의 앞선 356건을 UP-TO-DATE로 재사용했고, 전체 통합·이행과 bootJar는 실행했다. 변경하지 않은 Order·Gateway·License의 전체 검증을 다시 실행한 것으로 주장하지 않는다. 소스 1,216개가 전송 명세와 일치하며 Payment JAR SHA-256은 `700ed87e3531b5c7d0e335d7202ad5098d19ffa30a381aed310504da61f2a5dc`다.

검증 직후 첫 자원 비교에는 이번 migrationTest의 Testcontainers Ryuk 하나가 종료 대기로 남았다. 자동 종료 뒤 다시 수집한 최종 비교는 기존 컨테이너 23개·볼륨 57개와 ID·시작 시각·재시작 횟수·실행/건강 상태가 모두 같다. 수동 삭제·공유 자료 초기화는 하지 않았다. 원자료와 로그·XML·소스/자원 대조는 `runs/g1-review-expiry-z4e2zyba/collected/`에 수집했다.

원래 종료된 실제 검증 스택·업무 DB는 재기동하거나 수정하지 않았다. 과거 REVIEW_REQUIRED 원본 기록은 당시 사실로 보존하며, 코드 수정과 원본 실행 환경에 대한 적용을 구분한다. 추가 PG 금융 동작은 없다.
