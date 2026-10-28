# STOVE Platform

창작자가 게임을 등록·심사·출시하고, 구매자가 결제·다운로드하며, 운영자가 판매자별 매출을 정산하는 이벤트 기반 게임 스토어 백엔드다. 서비스는 각자의 데이터를 관리하고 Kafka 이벤트로 처리 결과를 전달한다.

프로젝트가 처음이라면 **[도메인·모델 안내](docs/domain/model-guide.md)**부터 읽는다. 도메인별 모델의 속성, 생성·사용 시점과 게임 한 개가 출시되어 판매되는 흐름을 설명한다.

현재 재작성 완료 모듈은 **Auth·Gateway·Studio·Review·Catalog·Store·Order·Payment·License·Download·Settlement**다. 신규 구현과 검증은 이 열한 모듈의 계약을 기준으로 하며, 미완료 모듈의 기존 구현은 설계·검증 기준으로 삼지 않는다. Studio의 완료 범위와 검증 기록은 [단순화 계획·완료 기록](docs/studio-simplification-plan.md#재작성-완료-기록)을 따른다.

현재 목표는 이 MVP 기능들을 일반적인 사용 수준으로 올린 뒤 엣지 케이스·성능을 고도화하는 것이다. [전체 평가·개발 계획](docs/platform-generalization-plan.md)에 모듈별 공백, 환불·Payment 평가와 권장 작업 순서를 정리했다. MVP 완료와 전체 일반화 완료를 구분한다.

Catalog의 완료 범위와 검증 결과는 [MVP 계획·완료 기록](docs/catalog-mvp-plan.md#재작성-완료-기록)에 정리했다.
Store의 학습용 최소 범위와 OCI 검증 결과는 [Store 계획·완료 기록](docs/store-mvp-plan.md#재작성-완료-기록)을 따른다.
Order의 단일 상품 주문·결제 결과 계약과 OCI 검증은 [Order 완료 기록](docs/order-mvp-plan.md#재작성-완료-기록), 실행은 [Order 안내](docs/order-guide.md)를 따른다. Payment의 모의 PG 자동 처리·복구·조회와 8개 앱 OCI 검증은 [Payment 완료 기록](docs/payment-mvp-plan.md#재작성-완료-기록), 실행은 [Payment 안내](docs/payment-guide.md)를 따른다.

License의 상품 보유권·구매 근거·내 라이브러리와 아홉 앱 검증은 [License 완료 기록](docs/license-mvp-plan.md#통합-수정-완료-기록), 실행은 [License 안내](docs/license-guide.md)를 따른다. Download의 보유권·현재 공개 빌드 조회와 전체 ZIP 만료 URL 제공은 [완료 기록](docs/download-mvp-plan.md#재작업-완료-기록), 실행은 [Download 안내](docs/download-guide.md)를 따른다. Settlement의 외부 모의 PG·배분·월 마감·수령 대사는 [완료 기록](docs/settlement-mvp-plan.md#재작성-완료-기록), 전체 실행은 [Settlement 안내](docs/settlement-guide.md)를 따른다.

PortOne TEST의 전액 환불은 고객 접수·관리자 판정·이용권 정지/복구·취소/조회, 고객 재시도·재심사·승인 정정, 운영 중단 조회/재개와 장기 미확정 조사 기록까지 확장했다. 실제 자동 환불 1건의 근거와 각 확장 검증을 구분하며 최신 관리자 전체 여정·정산 환불 조정 등은 남아 있다. [현재 환불 인계](docs/refund-session-handoff.md) · [환불 세션 공통 지시](docs/refund-next-session.md)

## 문서 찾기

| 궁금한 내용 | 읽을 문서 |
|---|---|
| 현재 목표·모듈 평가·다음 개발 순서 | [전체 기능 일반화 계획](docs/platform-generalization-plan.md) |
| 어떤 도메인과 모델이 있고 어떻게 연결되는가 | [도메인·모델 안내](docs/domain/model-guide.md) |
| 업무 용어와 행위자별 처리 순서 | [용어집](docs/domain-glossary.md) · [업무 흐름](docs/domain/workflows.md) |
| 아직 결정되지 않은 정책과 구현 차이 | [미결 질문](docs/domain/open-questions.md) |
| 로컬·원격 실행과 Swagger 로그인 | [개발 환경과 실행](docs/getting-started.md) |
| 출시·구매·다운로드·정산의 실제 흐름 검증 | [Settlement 전체 실행](docs/settlement-guide.md) |
| 서비스 API와 인증·권한 | [서비스 명세](docs/services.md) · [인증·권한 계약](docs/security.md) |
| 구조와 설계 이유, 실험·운영 기록 | [아키텍처](docs/architecture.md) · [전체 문서 목록](docs/README.md) |

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

현재 열 앱의 실제 구매·다운로드 검증은 [Download 독립 검증](docs/download-guide.md#로컬-orbstack-검증)을 사용한다. [전체 API 데모](docs/api-demo.md#full-demo)는 구매·배포·정산 모듈의 재작성 전 참고 기록이다. 실제 PG·세금계산서 등 외부 연동의 구현 범위는 [서비스 명세](docs/services.md)에 정리돼 있다.
