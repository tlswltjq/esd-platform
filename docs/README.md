# 문서 안내

처음 읽을 때는 [도메인·모델 안내](domain/model-guide.md) → [업무 흐름](domain/workflows.md) → 필요한 모델 사전 순서로 읽는다. 모델 사전에는 필드뿐 아니라 생성·사용 시점, 업무 규칙 제안과 코드·테스트 근거가 함께 있다.

## 업무와 코드 이해

| 문서 | 다루는 내용 |
|---|---|
| [도메인·모델 안내](domain/model-guide.md) | 전체 지도, 모델 사전 연결, 한 게임의 출시·구매 사례 |
| [용어집](domain-glossary.md) | 표준 용어 색인, 헷갈리는 상태·식별자·버전 |
| [업무 흐름](domain/workflows.md) | 창작자·심사자·구매자·운영자별 순서와 이벤트 |
| [미결 질문](domain/open-questions.md) | 합의가 필요한 업무 정책, 구현 차이와 검토 기록 |
| [서비스 명세](services.md) | 서비스별 API·상태·이벤트와 외부 어댑터 |

## 실행과 실습

| 문서 | 다루는 내용 |
|---|---|
| [개발 환경과 실행](getting-started.md) | 로컬·원격 실행, Swagger OAuth, 문제 해결 |
| [API 데모](api-demo.md) | 전체 자동 검증과 창작·구매 수동 호출 예제 |
| [인증·권한 계약](security.md) | 계정·역할·소유권, access token과 PG 콜백 |
| [auth·Gateway/BFF](auth-bff.md) | 재작업 완료 모듈의 자동 로그인·RTR·공유 세션, 실행과 검증 |
| [Studio 실행·심사·출시 요청](studio-guide.md) | Auth·Gateway 기준의 전체 업로드·심사·출시 요청과 세 모듈 검증 |
| [Review 실행·수동 심사](review-guide.md) | 심사자 로그인·ZIP 열람·승인 및 반려·네 모듈 검증과 재시작 확인 |
| [Catalog 실행·출시 검증](catalog-guide.md) | 공개 API, 다섯 모듈 독립 검증, 기존 DB·Outbox 전환 절차 |
| [Store 실행·검색·사본 확인](store-guide.md) | OCI 검증, 제목 검색·상세, HTTP 초기화와 재시작 복구 |

## 설계·검증·운영 근거

| 문서 | 다루는 내용 |
|---|---|
| [아키텍처](architecture.md) | 서비스 경계, 트랜잭션·이벤트 처리, DB 실험과 설계 위험 |
| [유스케이스 작성 규칙](use-case-conventions.md) | SRP에 따른 책임 분리, 동사형 클래스·메서드 이름, 트랜잭션 유지 기준 |
| [공통 모듈](common-modules.md) | Web·Security·JPA·Kafka·Messaging 책임, 의존성과 실행 환경 |
| [Studio 단순화 계획·완료 기록](studio-simplification-plan.md) | Project 중심의 전체 빌드 업로드·심사 및 출시 요청·재요청 범위와 완료·검증 기록 |
| [Review 개선 계획·완료 기록](review-simplification-plan.md) | 단일 스냅샷 심사·빌드 열람·승인 및 반려·결과 이벤트의 범위와 완료·검증 기록 |
| [Catalog MVP 계획·완료 기록](catalog-mvp-plan.md) | 출시 처리·공개본·공개 조회의 범위, 버전·중복 정책과 실제 검증 결과 |
| [Store MVP 계획·완료 기록](store-mvp-plan.md) | 학습용 최소 검색·조회 사본, Catalog 연동과 OCI 실제 검증 결과 |
| [설계 결정](decisions.md) | 선택한 방식과 버린 대안, 결정 당시의 이유 |
| [이벤트 순서](event-ordering.md) | 정상 발행과 재시도·재투입의 순서 보장 조건 |
| [테스트 전략](testing.md) | 단위·통합·계약·E2E에서 검증하는 범위 |
| [결함 기록](defects.md) | 재현한 결함, 원인·수정·회귀 검증 |
| [성능 측정](performance.md) · [측정 원칙](measuring.md) | 요약과 원자료, 유효·무효 판정 기준 |
| [장애 실험](chaos.md) · [재시작 시나리오](resilience-scenarios.md) | 부하 중 장애와 중단 후 처리 재개 근거 |
| [이용권 DB 복구 절차](runbooks/license-db-loss.md) | 원장 유실 시 복구와 확인 순서 |
| [DB 락 실험](../scripts/db-lab/README.md) · [성능 도구](../scripts/perf/README.md) · [장애 도구](../scripts/chaos/README.md) | 실험 실행 방법과 결과 파일 |

## 문서를 갱신할 때

모델·속성·업무 규칙은 모델 사전, 미결 정책은 미결 질문, API 계약은 서비스 명세, 실행 명령은 실행·데모 문서에 갱신한다. 다른 문서에서는 해당 설명을 링크한다. 단계명이나 이슈 번호별로 같은 안내를 새로 만들지 않는다.

날짜가 있는 결정·결함·실험 기록은 당시의 근거다. 현재 코드에서 다시 검증하지 않은 결과를 최신 상태로 바꾸지 않는다. 브랜치·열린 PR·세션 진행 상황은 Git과 이슈에서 확인하고 별도 상태 문서로 중복 관리하지 않는다.
