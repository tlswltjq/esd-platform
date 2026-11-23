---
name: verify-tests
description: esd-platform에서 Gradle 테스트 결과를 통과로 보고하기 전에 — 단위·통합 테스트 실행, 반복 실행(N회 통과) 판정.
---

판정 규칙의 정본은 `tools/ci/gradle_result.py`다(결정 213). 범위·명령 선택은 `docs/testing.md`를 따른다.

- `timeout <초> python3 tools/ci/gradle_result.py [--repeat N] -- ./gradlew <작업>`
- 종료 코드 0과 "통과: N회 모두" 줄이 있을 때만 통과로 보고한다. test-results XML을 직접 읽어 판정하지 않는다.
- 실패하면 출력의 실패 테스트와 Gradle 출력 끝을 그대로 근거로 쓴다.
