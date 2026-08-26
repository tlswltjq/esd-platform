# 문서 지도

시스템 전체 그림·이벤트 흐름·실행 방법은 [README](../README.md) 에 있다.
여기는 그보다 한 층 아래다 — **왜 그렇게 했고, 무엇이 틀렸었고, 무엇을 어떻게 검증하는가.**

---

## 처음 읽는다면

| 순서 | 문서 | 왜 이 순서인가 |
|---|---|---|
| 1 | [services.md](services.md) | 서비스 9종이 무엇을 받고 무엇을 내보내는지. 나머지 문서가 쓰는 지명이 전부 여기서 나온다 |
| 2 | [decisions.md](decisions.md) | 구조를 이렇게 잡은 근거 22건. **버린 선택지와 대가**까지 있어 결론만 읽는 것보다 값이 크다 |
| 3 | [defects.md](defects.md) | 재현 테스트가 붙은 결함 36건. **패턴 학습보다 함정 학습이 빠르다** |
| 4 | [event-ordering.md](event-ordering.md) | "같은 애그리거트의 순서 보장"이 세 층에 어떻게 나뉘는가 |
| 5 | [kafka-consumer-retry.md](kafka-consumer-retry.md) | 컨슈머 재시도가 예외 전파에 기대는 이유. 4번의 컨슈머 층 후속편 |
| 6 | [testing.md](testing.md) | 무엇을 어느 층에서 검증하고, 왜 그 층인가 |

---

## 상시 문서 — 코드가 바뀌면 같이 바뀐다

| 문서 | 무엇이 있나 |
|---|---|
| [services.md](services.md) | 서비스별 API·상태머신·이벤트·규칙, 외부 연동 대역(무엇이 실동작이고 무엇이 흉내인지) |
| [decisions.md](decisions.md) | 설계 결정 22건 — 배경 → 결정 → 근거 → 버린 선택지. 끝에 "검증하며 드러난 것" |
| [defects.md](defects.md) | 결함 대장 36건 (살아 있는 것 1건 — [D-026](defects.md#d-026)). **추측은 넣지 않는다** — 전부 재현 테스트가 하나씩 붙어 있다 |
| [review-log.md](review-log.md) | 코드 리뷰 지적의 전수 판정. 고친 것·넘긴 것·**틀린 지적**과 다시 볼 조건 |
| [testing.md](testing.md) | 테스트 계층 L0~L7, 격리, 뮤테이션 테스트, 아직 남은 공백 |
| [event-ordering.md](event-ordering.md) | 순서가 깨지는 3층위 + 해법 카탈로그 6종 + 릴레이 1대 제약 |
| [performance.md](performance.md) | 릴레이 처리량 138 → 480.5 events/s, HTTP 경로 재측정(9장), **받는 쪽 측정(12·13장)** |
| [measuring.md](measuring.md) | **측정을 어떻게 하는가.** 규칙 11개와 각 규칙을 만든 사건. 새 측정을 시작하기 전에 읽는다 |
| [chaos.md](chaos.md) | **장애를 넣고 재는 것은 무엇이 다른가.** 부하 중에 DB 를 끊고 보상·재시도·가드·DLT 가 버티는지 |
| [resilience-scenarios.md](resilience-scenarios.md) | **서버가 중단됐을 때 무엇이 보장되는가** — 시나리오 R-01~R-06 과 각각을 지키는 테스트. chaos.md 가 "이번 회차에 이랬다" 라면 여기는 "다음에도 그래야 한다" 다 |
| [runbooks/](runbooks/) | 복구 절차. 각 줄이 무엇을 막는지 숫자로 확인된 것만 적는다 |

측정 도구는 [scripts/perf/](../scripts/perf/)(k6 시나리오·랙 수집·회차 러너)와
[scripts/chaos/](../scripts/chaos/)(장애 주입기·회차 러너·복구 실행본)에 있다.
**결과가 performance.md · chaos.md 라면 방법은 measuring.md 다** — 7장이 통째로 무효였던 것 같은 일을
다시 겪지 않으려고 규칙을 한자리에 모았다.

## 노트·기록 — 특정 시점의 산물이다

코드를 따라가지 않으므로 **날짜와 상단 배너를 먼저 본다.**

| 문서 | 성격 |
|---|---|
| [kafka-consumer-retry.md](kafka-consumer-retry.md) | 학습 노트. **4·6절은 수정 전 상태**이고 상단 배너가 지금을 알린다 |
| [handover.md](handover.md) | 학습 세션 인계노트(2026-08-06~07). 열린 질문 ① 하나가 아직 남아 있다 |
| [remote-dev-plan.md](remote-dev-plan.md) | 원격 CI 구축 계획·실행 기록. **Phase 7(CD)이 끝나면 지운다** |
| [test-audit.md](test-audit.md) | 테스트 점검(2026-08-10). 소견 F1~F6 과 채울 순서 5단계. **순서를 다 밟으면 지운다** |
| [perf-tuning.md](perf-tuning.md) | 설정 튜닝 측정(2026-08-10). performance.md 8-3·8-4·11-2 를 숫자로 닫는다. **코드 변경 없음** |

---

## 갱신 규칙

- **무엇을 했는지는 커밋이 말한다.** 문서에는 *왜* 그렇게 했고 *무엇을 포기했는지*를 적는다.
- 결함은 **고치기 전에 재현 테스트부터** 커밋한다(`@Tag("known-defect")`).
  단언은 현재 동작이 아니라 **의도한 올바른 동작**으로 쓴다 — 규칙은 [testing.md](testing.md) 3절.
- **수정 전 서술을 지우지 않는다.** 무엇이 틀렸었는지가 결론만큼 값이 크다.
  대신 후속 변경을 배너나 인용으로 덧붙여 지금 상태를 알린다
  (예: [performance.md](performance.md) 7장의 무효한 측정을 남겨 둔 것).
- **강제할 수 없는 문장은 대개 사실이 아니다**([decisions.md](decisions.md) 13번).
  코드로 확인할 수 있는 것은 문서 대신 규칙과 테스트에 적는다 —
  ArchUnit, OpenAPI 스냅샷, `ArchRuleEnforcementTest` 가 그 자리다.
- 문서가 코드와 어긋난 것을 발견했을 때 **틀린 쪽이 코드라는 보장은 없다**(같은 항목).
