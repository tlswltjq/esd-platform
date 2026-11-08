# 문서 안내

문서는 역할별로 한 곳씩만 둔다. 작업 규칙과 문서를 어디에 쓸지는 [AGENTS.md](../AGENTS.md)를 따른다.

## 처음 읽는다면

1. [아키텍처](architecture.md) — 서비스 경계, 트랜잭션·이벤트 처리, ArchUnit으로 강제하는 규칙
2. [도메인·모델 안내](domain/model-guide.md) → [업무 흐름](domain/workflows.md) → [용어집](domain-glossary.md)
3. [판단 색인](judgment-index.md) — 어떤 쟁점에서 누가 무엇을 골랐나 (대화 원문 인용)
4. [근거 색인](evidence.md) — 검증 결과 한 줄씩 (날짜·대상·수치·원문)

## 역할별 정본

| 무엇 | 문서 |
|---|---|
| 모듈별 현재 계약·남은 작업 | [`.kiro/specs/`](../.kiro/specs/README.md) — 모듈마다 `requirements`·`design`(끝에 "사용 안내")·`tasks` |
| 단계·순서·완료 조건 | [로드맵](roadmap.md) |
| 왜 그렇게 했고 무엇을 버렸나 | [설계 결정](decisions.md) |
| 사용자의 판단 이력 | [판단 색인](judgment-index.md) |
| 검증 결과 | [근거 색인](evidence.md) |
| 재현된 결함 | [결함 대장](defects.md) |
| 성능·장애 실험 | [실험 기록](experiments.md) |
| 미결 업무 정책 | [미결 질문](domain/open-questions.md) |

## 기준 문서

| 문서 | 다루는 내용 |
|---|---|
| [서비스 명세](services.md) | 서비스별 API·상태·이벤트와 외부 어댑터 |
| [이벤트 순서](event-ordering.md) | 정상 발행과 재시도·재투입의 순서 보장 조건 |
| [인증·권한 계약](security.md) | 계정·역할·소유권, 서비스 접근키, 배포 비밀 주입, 웹훅 수신 경계 |
| [auth·Gateway/BFF](auth-bff.md) | 자동 로그인·RTR·공유 세션 |
| [공통 모듈](common-modules.md) | Web·Security·JPA·Kafka·Messaging 책임과 의존성 |
| [유스케이스 작성 규칙](use-case-conventions.md) | 책임 분리, 동사형 이름, 트랜잭션 기준 |
| [테스트 전략](testing.md) | 단위·통합·계약·E2E 범위와 실행 명령 |
| [Compose 실행 구성](compose-layout.md) | 기본·로컬 Compose 파일과 포트 |
| [모델 사전](domain/models/) | 모델별 필드·생성·사용 시점·업무 규칙 |

## 실행과 운영

| 문서 | 다루는 내용 |
|---|---|
| [개발 환경과 실행](getting-started.md) | 로컬 실행, Swagger OAuth, 문제 해결 |
| [API 데모](api-demo.md) | 전체 자동 검증과 창작·구매 수동 호출 예제 |
| [백업과 장애 복구](backup-restore.md) | MySQL 전체 백업 + binlog 연속 복사, 빌드 연속 미러, 복구 순서와 격리 연습 결과 |
| [License 이벤트 복구 절차](license-event-recovery-runbook.md) | 지급·확인 이벤트의 DLT 재투입·재큐잉 |
| [환불 지연·귀속 미확정 종결 절차](runbooks/refund-delay-closure.md) | 장기·귀속 미확정 환불의 콘솔 취소 → PG 재조회 근거 종결(결정 127) |
| [이용권 DB 복구 절차](runbooks/license-db-loss.md) | 재작성 전 계약의 원장 유실 복구 참고 기록 (현재 License에 적용하지 않음) |
| [tools 안내](../tools/README.md) | 검증·관측·DB 실험 도구 |

## 문서를 갱신할 때

- 새 문서를 만들지 않는다. 계획과 검증 상세는 PR 본문에, 결과 한 줄은 [근거 색인](evidence.md)에 둔다.
- 같은 내용을 두 곳에 쓰지 않는다. 계약은 스펙, 이유는 결정, 수치는 근거 색인에 두고 나머지는 링크한다.
- 날짜가 있는 결정·결함·실험 기록은 당시의 근거다. 다시 검증하지 않은 결과를 최신 상태로 바꾸지 않는다.
- 2026-11-06 문서 통합 전의 계획·검증·인계 문서 원문은 고정 커밋 [`0cc225bf`](https://github.com/tlswltjq/esd-platform/tree/0cc225bf/docs)에서 볼 수 있다.
