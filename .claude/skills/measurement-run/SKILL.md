---
name: measurement-run
description: esd-platform에서 성능·장애 측정 회차나 Docker를 쓰는 격리 통합 검증을 돌리기 전과 후.
---

격리·보존 판정의 정본은 AGENTS.md 5절과 `tools/ci/measure_guard.py`다(결정 213). 측정 방법은 `docs/experiments.md`를 따른다.

1. 실행 전: `python3 tools/ci/measure_guard.py before --name <회차 이름> --allow <측정 컨테이너 이름 앞부분>`
2. 측정을 돌린다.
3. 실행 뒤: `python3 tools/ci/measure_guard.py after --name <회차 이름>`
4. 어느 단계든 실패 출력은 사용자에게 그대로 보고한다.
