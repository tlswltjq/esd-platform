# 개발·검증 도구

저장소 루트에서 실행한다. 코드·문서는 로컬에서 편집하고 빌드·테스트·서비스 실행은 로컬 호스트의 격리 환경에서 수행한다(AGENTS.md 5절).

| 디렉터리 | 용도 |
|---|---|
| [verification](verification/README.md) | 모듈별 격리 검증, Jib 전체 흐름, Compose 상태 검사 |
| [observability](observability/README.md) | Outbox·컨슈머·브로커 랙·알람의 읽기 전용 수집 |
| [ci/review_gate.py](ci/review_gate.py) | PR 본문 `리뷰 판정` 표 확인: 판정 칸, 사용자 판정 대상(부분 반영·기각·정책), `할 일로 이관`의 위치·high 금지([결정 109](../docs/decisions.md#review-gate)·[결정 ai-judges-adopted-high](../docs/decisions.md#ai-judges-adopted-high)·[결정 rereview-cap-two](../docs/decisions.md#rereview-cap-two)). 테스트: `python3 -B -m unittest discover -s tools/ci -p test_review_gate.py -v` |
| [ci/decision_numbers.py](ci/decision_numbers.py) | 결정 번호(1~238) 중복·오름차순·새 번호 금지, 새 결정의 식별자 형식, 앵커 중복, 링크 문구와 앵커 일치 확인([결정 116](../docs/decisions.md#decision-numbers)·[결정 decision-name-ids](../docs/decisions.md#decision-name-ids)). 테스트: `python3 -B -m unittest discover -s tools/ci -p test_decision_numbers.py -v` |
| [ci/start_task.py](ci/start_task.py) | 착수: worktree·착수 커밋·PR 본문(`prepare`), push 확인 뒤 draft PR(`open`). push는 출력만 한다([결정 213](../docs/decisions.md#ai-procedure-tools)). 테스트: `python3 -B -m unittest discover -s tools/ci -p test_start_task.py -v` |
| [ci/gradle_result.py](ci/gradle_result.py) | Gradle 테스트를 이번 실행이 만든 XML과 종료 코드로만 판정, `--repeat N`([결정 213](../docs/decisions.md#ai-procedure-tools)). 테스트: `python3 -B -m unittest discover -s tools/ci -p test_gradle_result.py -v` |
| [ci/pr_finish.py](ci/pr_finish.py) | 필수 검사 등록·완료 대기(`wait`), ready 전환(같은 head 결과로 판정)·본문 수정 뒤 다시 도는 review-gate만 새 job을 기다림(`ready`·`edit`), 병합 조건 판정(main CI 실패 시 고치는 PR만, `check`), 순서대로 하나씩 병합(`merge`)([결정 213](../docs/decisions.md#ai-procedure-tools)). 테스트: `python3 -B -m unittest discover -s tools/ci -p test_pr_finish.py -v` |
| [ci/measure_guard.py](ci/measure_guard.py) | 측정·격리 검증 전후 컨테이너·볼륨·네트워크 기록·대조, 무관한 실행 중 컨테이너 경고([결정 213](../docs/decisions.md#ai-procedure-tools)). 테스트: `python3 -B -m unittest discover -s tools/ci -p test_measure_guard.py -v` |
| [operations/license-recovery.sql](operations/license-recovery.sql) | [운영 재처리 절차](../docs/runbooks/license-event-recovery.md)의 주문·지급·확인 읽기 전용 대조 |
| [db-lab](db-lab/README.md) | 격리 MySQL의 락·동시성 학습 실험 |
| [migrations](migrations/README.md) | 버전을 지정한 기존 자료 전환·사전 점검 |
| [image-benchmark](image-benchmark/README.md) | 고정 과거 revision의 이미지 비교 재현 |
| [remote](remote/README.md) | 기존 원격 환경 관리 |

`scripts/`에는 `local-stack.sh`, `start-apps.sh`만 남긴다. 구형 주문·콜백·환불 기반 성능·장애 시나리오와 공유 Outbox를 초기화하는 릴레이 실험은 제거했다. 결과 문서는 과거 기록으로 보존한다. 현재 목적의 성능·장애 실험은 완료된 모듈 계약과 격리 환경을 기준으로 별도 정의한다.
