# 개발·검증 도구

저장소 루트에서 실행한다. 현재 검증 환경은 로컬 OrbStack이다.

| 디렉터리 | 용도 |
|---|---|
| [verification](verification/README.md) | 모듈별 격리 검증, Jib 전체 흐름, Compose 상태 검사 |
| [observability](observability/README.md) | Outbox·컨슈머·브로커 랙·알람의 읽기 전용 수집 |
| [db-lab](db-lab/README.md) | 격리 MySQL의 락·동시성 학습 실험 |
| [migrations](migrations/README.md) | 버전을 지정한 기존 자료 전환·사전 점검 |
| [image-benchmark](image-benchmark/README.md) | 고정 과거 revision의 이미지 비교 재현 |
| [remote](remote/README.md) | 기존 원격 환경 관리 |

`scripts/`에는 `local-stack.sh`, `start-apps.sh`만 남긴다. 구형 주문·콜백·환불 기반 성능·장애 시나리오와 공유 Outbox를 초기화하는 릴레이 실험은 제거했다. 결과 문서는 과거 기록으로 보존한다. 현재 목적의 성능·장애 실험은 완료된 모듈 계약과 격리 환경을 기준으로 별도 정의한다.
