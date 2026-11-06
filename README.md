# STOVE Platform

창작자가 게임을 등록·심사·출시하고, 구매자가 결제·다운로드하며, 운영자가 판매자별 매출을 정산하는 이벤트 기반 게임 스토어 백엔드다. 서비스는 각자의 데이터를 관리하고 Kafka 이벤트로 처리 결과를 전달한다.

프로젝트가 처음이라면 **[도메인·모델 안내](docs/domain/model-guide.md)**부터 읽는다. 도메인별 모델의 속성, 생성·사용 시점과 게임 한 개가 출시되어 판매되는 흐름을 설명한다.

열한 모듈(**Auth·Gateway·Studio·Review·Catalog·Store·Order·Payment·License·Download·Settlement**)의 MVP 재작성을 마쳤고, 지금은 이 기능들을 일반적인 사용 수준으로 올린 뒤 엣지 케이스·성능을 고도화하는 단계다([로드맵](docs/roadmap.md)). 결제·환불은 PortOne TEST 채널에서 실제 승인·자동/수동 전액 환불·이용권 정지/복구까지 검증했다([근거 색인](docs/evidence.md)).

## 어떻게 만드는가

기능은 AI 에이전트(Codex·Claude·Kiro)와 채팅으로 설계한다. 에이전트가 쟁점·대안·트레이드오프·추천을 제시하면 내가 의견을 내고 선택하며, 구현·테스트·검증은 에이전트가 맡는다. 공통 규칙은 [AGENTS.md](AGENTS.md)에 있고, 구조 규칙은 ArchUnit으로 강제한다.
누가 무엇을 골랐는지는 대화 원문을 인용한 [판단 색인](docs/judgment-index.md)에, 각 결정의 이유와 버린 선택지는 [설계 결정](docs/decisions.md)에 있다.

## 문서 찾기

| 궁금한 내용 | 읽을 문서 |
|---|---|
| 현재 목표·단계·완료 조건 | [로드맵](docs/roadmap.md) |
| 모듈별 계약·실행 방법·남은 작업 | [`.kiro/specs/`](.kiro/specs/README.md) (각 `design.md` 끝의 "사용 안내") |
| 어떤 도메인과 모델이 있고 어떻게 연결되는가 | [도메인·모델 안내](docs/domain/model-guide.md) · [용어집](docs/domain-glossary.md) · [업무 흐름](docs/domain/workflows.md) |
| 누가 무엇을 왜 골랐나 | [판단 색인](docs/judgment-index.md) · [설계 결정](docs/decisions.md) |
| 무엇으로 검증했나 | [근거 색인](docs/evidence.md) · [실험 기록](docs/experiments.md) · [결함 대장](docs/defects.md) |
| 로컬 실행과 Swagger 로그인 | [개발 환경과 실행](docs/getting-started.md) · [API 데모](docs/api-demo.md) |
| 서비스 API와 인증·권한 | [서비스 명세](docs/services.md) · [인증·권한 계약](docs/security.md) |
| 구조와 전체 문서 목록 | [아키텍처](docs/architecture.md) · [문서 안내](docs/README.md) |

## 구성

Java 21, Spring Boot, Gradle 멀티 모듈을 사용한다. 업무 데이터와 Store 조회 사본은 MySQL, Gateway 세션은 Redis, 이벤트는 Kafka, 업로드 파일은 MinIO/S3를 사용한다. Download는 별도 업무 저장소 없이 License·Catalog를 조회한다. 구 MongoDB 자료는 보존하지만 새 업무에서 사용하지 않는다.

| 위치 | 책임 |
|---|---|
| `apps/auth` | 회원 가입, 역할, OAuth2/OIDC 토큰 |
| `apps/studio` | 출시 상세정보·전체 빌드 관리, 심사·출시 요청과 재요청 |
| `apps/review` | 불변 Project 스냅샷·빌드 열람, 수동 승인·반려와 결과 발행 |
| `apps/catalog` | 출시 요청 처리, 공개 상품·공개본 정본과 공개 조회 |
| `apps/store` | MySQL 공개 상품 사본, 제목 검색·목록·상세와 초기 동기화 |
| `apps/order` | 단일 상품 주문 스냅샷·청구액·내 주문 조회와 결제 결과 반영 |
| `apps/payment` | 확정 주문 결제·PortOne TEST 결제창/웹훅·환불 실행/조회·미확정 재개 |
| `apps/license` | 구매 출처·상품별 영구 보유권·내 라이브러리 |
| `apps/download` | 보유권·현재 공개 빌드 확인·전체 ZIP 만료 URL |
| `apps/settlement` | OrderPaid 매출·판매자 배분·월 마감·PG 지급 명세 수령 대사 |
| `simulators/mock-pg` | 독립 프로세스·전용 DB·HTTP 결제 결정·비용·모의 지급 명세 |
| `apps/gateway` | 외부 API와 로그인 진입점 |
| `common/` | 이벤트 계약, Outbox·Inbox, 웹·JPA·테스트 지원 |
| `infra/`, `docker-compose*.yml` | 저장소·메시징·관측 도구와 앱 실행 구성 |
| `e2e/`, `tools/verification/` | 인수 테스트, 격리 스택·이미지·인증 검증 도구 |
| `tools/` | 검증·관측·DB 실험·전환·이미지 벤치마크·원격 도구 |
| `scripts/` | 로컬 스택 관리와 앱 기동 |

## 빠른 시작

빌드·테스트·서비스 실행은 로컬 OrbStack에서 수행한다. 저장소 루트에서 Docker 컨텍스트를 지정한 뒤 검증한다.

```bash
export DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
python3 tools/verification/verify-settlement.py
```

스택이 준비되면 [Swagger UI](http://127.0.0.1:18080/swagger-ui.html)를 연다. 로그인까지 같은 주소를 사용한다. 실행 조건, 상태·로그 확인, 종료와 원격 실행은 [개발 환경과 실행](docs/getting-started.md)을 따른다.

현재 열 앱의 실제 구매·다운로드 검증은 [Download 독립 검증](.kiro/specs/download/design.md#사용-안내)을 사용한다. [전체 API 데모](docs/api-demo.md#full-demo)는 구매·배포·정산 모듈의 재작성 전 참고 기록이다. 실제 PG·세금계산서 등 외부 연동의 구현 범위는 [서비스 명세](docs/services.md)에 정리돼 있다.
