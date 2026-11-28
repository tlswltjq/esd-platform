---
name: cross-review
description: esd-platform 코드 PR(변경 파일 중 *.md가 아닌 것이 있음)을 병합하기 전에 Codex 교차 리뷰·재리뷰를 받을 때.
---

호출·확인 규칙의 정본은 AGENTS.md 5절과 `tools/ci/cross_review.py`다(결정 237). 이 파일은 호출 순서만 적는다(결정 213).

1. 묶음: `python3 tools/ci/review_packet.py --pr-body <본문 파일> --out <묶음>` (재리뷰는 `--since <리뷰한 커밋> --previous <이전 지적>`)
2. 리뷰: `timeout 3700 python3 tools/ci/cross_review.py run --packet <묶음> --out <결과.md> --root <PR worktree>`
3. `통과:`로 시작하는 줄의 리뷰어 문구를 PR 본문 `리뷰 판정`에 그대로 적는다. `실패:`면 그 결과(`<결과.md>.unverified`)는 리뷰로 쓰지 않고, 출력을 사용자에게 그대로 보고한다.
