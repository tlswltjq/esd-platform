---
name: verify-tests
description: esd-platform에서 Gradle 테스트 결과를 통과로 보고하기 전에 — 단위·통합 테스트 실행, 반복 실행(N회 통과) 판정.
---

판정의 정본은 `tools/ci/gradle_result.py`다(결정 213). 범위·명령 선택은 `docs/testing.md`를 따른다.

- `timeout <초> python3 tools/ci/gradle_result.py [--repeat N] -- ./gradlew <작업>`
- 보고에는 스크립트의 판정 출력을 그대로 쓴다.
