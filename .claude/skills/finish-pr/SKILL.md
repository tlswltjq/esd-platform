---
name: finish-pr
description: esd-platform PR을 push한 뒤 CI를 기다릴 때, 그리고 병합 조건을 확인하거나 병합할 때(여러 PR을 순서대로 병합할 때 포함).
---

병합 조건의 정본은 AGENTS.md 5절과 `tools/ci/pr_finish.py`다(결정 213).

1. push 직후: `timeout 3700 python3 tools/ci/pr_finish.py wait <PR> --head-sha "$(git rev-parse HEAD)"`
   - `gh pr ready`·본문 수정 직후(head는 그대로): `timeout 3700 python3 tools/ci/pr_finish.py wait <PR> --new-runs`
2. 판정만: `python3 tools/ci/pr_finish.py check <PR> [<PR> …]`
3. 병합: `python3 tools/ci/pr_finish.py merge <PR> [<PR> …]` (병합 순서대로)
4. 어느 단계든 끝나면 출력을 사용자에게 바로 보고한다. "사용자 판정이 남은 지적"은 AGENTS 5절의 판정 절차로 넘긴다.
