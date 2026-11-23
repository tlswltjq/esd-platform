---
name: start-task
description: esd-platform에서 새 작업(PR)을 착수할 때 — 결정 번호 예약, worktree·브랜치, 착수 커밋, draft PR. 사용자에게 결정을 묻기 전에도 먼저 쓴다.
---

규칙의 정본은 AGENTS.md 1절과 `tools/ci/start_task.py`다. 이 파일은 호출 순서만 적는다(결정 213).

1. `python3 tools/ci/start_task.py prepare --branch <브랜치> --item '<맡은 항목 원문>' --decisions <예약할 개수> --trailer '<세션의 Co-Authored-By 줄>'`
2. 출력된 `git push origin <브랜치>`를 **다른 명령과 잇지 않고 단독으로** 실행한다.
3. `python3 tools/ci/start_task.py open --branch <브랜치> --title '<제목>'`
4. 출력된 worktree에서 작업한다. 결정 번호는 PR이 열린 뒤에 사용자에게 쓴다.
