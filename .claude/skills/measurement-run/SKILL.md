---
name: measurement-run
description: esd-platform에서 성능·장애 측정 회차나 Docker를 쓰는 격리 통합 검증을 돌리기 전과 후.
---

격리·보존 규칙의 정본은 AGENTS.md 5절과 `tools/ci/measure_guard.py`다(결정 213). 측정 방법은 `docs/experiments.md`를 따른다.

1. 실행 전: `python3 tools/ci/measure_guard.py before --name <회차 이름> --allow <측정 컨테이너 이름 앞부분>`
   - 무관한 실행 중 컨테이너가 나오면 멈추고 사용자에게 멈출지 묻는다. 스스로 멈추거나 지우지 않는다.
2. 측정을 돌린다. 긴 실행은 PID로 기다린다.
3. 실행 뒤: `python3 tools/ci/measure_guard.py after --name <회차 이름>` — 보존 위반·정리 누락이 나오면 결과를 보고하기 전에 처리한다.
