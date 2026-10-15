# STOVE Platform

창작자가 게임을 등록·심사·출시하고, 구매자가 결제·다운로드하며, 운영자가 판매자별 매출을 정산하는 이벤트 기반 게임 스토어 백엔드다. 서비스는 각자의 데이터를 관리하고 Kafka 이벤트로 처리 결과를 전달한다.

프로젝트가 처음이라면 **[도메인·모델 안내](docs/domain/model-guide.md)**부터 읽는다. 도메인별 모델의 속성, 생성·사용 시점과 게임 한 개가 출시되어 판매되는 흐름을 설명한다.

현재 재작성 완료 모듈은 **Auth·Gateway·Studio·Review·Catalog**다. 신규 구현과 검증은 이 다섯 모듈의 계약을 기준으로 하며, 미완료 모듈의 기존 구현은 설계·검증 기준으로 삼지 않는다. Studio의 완료 범위와 검증 기록은 [단순화 계획·완료 기록](docs/studio-simplification-plan.md#재작성-완료-기록)을 따른다.

Catalog의 완료 범위와 검증 결과는 [MVP 계획·완료 기록](docs/catalog-mvp-plan.md#재작성-완료-기록)에 정리했다.

## 문서 찾기

| 궁금한 내용 | 읽을 문서 |
|---|---|
| 어떤 도메인과 모델이 있고 어떻게 연결되는가 | [도메인·모델 안내](docs/domain/model-guide.md) |
| 업무 용어와 행위자별 처리 순서 | [용어집](docs/domain-glossary.md) · [업무 흐름](docs/domain/workflows.md) |
| 아직 결정되지 않은 정책과 구현 차이 | [미결 질문](docs/domain/open-questions.md) |
| 로컬·원격 실행과 Swagger 로그인 | [개발 환경과 실행](docs/getting-started.md) |
| 가입부터 출시·구매·환불·월 마감까지 실습 | [API 데모](docs/api-demo.md) |
| 서비스 API와 인증·권한 | [서비스 명세](docs/services.md) · [인증·권한 계약](docs/security.md) |
| 구조와 설계 이유, 실험·운영 기록 | [아키텍처](docs/architecture.md) · [전체 문서 목록](docs/README.md) |

## 구성

Java 21, Spring Boot, Gradle 멀티 모듈을 사용한다. 트랜잭션 데이터는 MySQL, 검색·진열 사본은 Elasticsearch, 배포 매니페스트는 MongoDB, 캐시는 Redis, 업로드 파일은 MinIO/S3에 저장한다.

| 위치 | 책임 |
|---|---|
| `apps/auth` | 회원 가입, 역할, OAuth2/OIDC 토큰 |
| `apps/studio` | 출시 상세정보·전체 빌드 관리, 심사·출시 요청과 재요청 |
| `apps/review` | 불변 Project 스냅샷·빌드 열람, 수동 승인·반려와 결과 발행 |
| `apps/catalog` | 출시 요청 처리, 공개 상품·공개본 정본과 공개 조회 |
| `apps/store` | 검색·진열 사본 (재작성 전) |
| `apps/order`, `apps/payment` | 주문과 결제·환불 |
| `apps/license`, `apps/download` | 이용권과 파일 접근·배포 |
| `apps/settlement` | 매출·환불 원장과 판매자 월 마감 |
| `apps/gateway` | 외부 API와 로그인 진입점 |
| `common/` | 이벤트 계약, Outbox·Inbox, 웹·JPA·테스트 지원 |
| `infra/`, `docker-compose*.yml` | 저장소·메시징·관측 도구와 앱 실행 구성 |
| `e2e/`, `scripts/` | 인수 검증, 스택 관리, 성능·장애 실험 |

## 빠른 시작

저장소 루트에서 실행한다. Gradle 실행용 JDK가 필요하며, 전체 스택과 통합 테스트에는 Docker도 필요하다.

```bash
./gradlew test
./scripts/local-stack.sh up
```

스택이 준비되면 [Swagger UI](http://127.0.0.1:18080/swagger-ui.html)를 연다. 로그인까지 같은 주소를 사용한다. 실행 조건, 상태·로그 확인, 종료와 원격 실행은 [개발 환경과 실행](docs/getting-started.md)을 따른다.

전체 API 여정을 반복 검증하려면 [API 데모](docs/api-demo.md#full-demo)를 사용한다. 실제 PG·세금계산서 등 외부 연동의 구현 범위는 [서비스 명세](docs/services.md)에 정리돼 있다.
