---
name: finish-pr
description: esd-platform PR을 push한 뒤 CI를 기다릴 때, 그리고 병합 조건을 확인하거나 병합할 때(여러 PR을 순서대로 병합할 때 포함).
---

병합 조건의 정본은 AGENTS.md 5절과 `tools/ci/pr_finish.py`다(결정 213).

1. push 직후: `timeout 3700 python3 tools/ci/pr_finish.py wait <PR> --head-sha "$(git rev-parse HEAD)"` — 끝나면 결과를 바로 사용자에게 보고한다.
2. 병합 전 판정만: `python3 tools/ci/pr_finish.py check <PR> [<PR> …]`
3. 병합: `python3 tools/ci/pr_finish.py merge <PR> [<PR> …]` — 순서대로 하나씩, 막히면 멈춘다. 멈춘 이유를 사용자에게 보고한다.
4. "사용자 판정이 남은 지적"이 나오면 병합하지 말고 그 지적을 쉬운 말로 설명해 판정을 받는다. 정책을 바꾸는 지적은 스크립트가 알아보지 못하니 직접 확인한다.
