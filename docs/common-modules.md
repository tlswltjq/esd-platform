# 공통 모듈의 책임과 의존성

`core`의 응답·오류 계약은 `web`으로 통합한다. 이벤트 계약은 브로커와 무관한 `contract`에 두고, 메시징은 코어 하나와 같은 모양의 adapter 넷으로 나눈다([결정 209](decisions.md#messaging-symmetric-modules)).
공통 라이브러리는 앱의 구현을 의존하지 않으며 앱이 자신의 실행 환경과 업무 정책을 선택한다.

| 모듈 | 책임 |
|---|---|
| `web` | 응답 봉투, 확장 가능한 오류 계약, Servlet 예외 처리·추적·API 문서 지원 |
| `security` | API JWT 검증, 역할 변환, 검증된 principal의 식별 정보 추출·주입 |
| `jpa` | 영속성 감사와 공통 시간 필드 |
| `event` | 메시징 포트: 발행(`MessagePublisher`)·수신(`@EventSubscriber`·`EventHandler`·`InboundMessage`)·격리(`KeyQuarantine`)·미러 발행(`MirrorPublisher`). 의존 없음 |
| `contract` | 이벤트 계약: 토픽·이벤트 종류·payload, 봉투(`EventEnvelope`)·헤더 이름. `event`와 Jackson만 의존 |
| `messaging` | 메시징 코어: JPA 트랜잭션과 연결되는 Outbox·릴레이(키 순서)·Inbox, 소비 격리·키 단위 재투입, 운영 API, 추적 전파. 브로커 클라이언트에 의존하지 않는다 |
| `messaging-kafka` | Kafka adapter: 발행·구독·제자리 재시도(`DefaultErrorHandler`), 격리 포트 연결, 미러 발행기, DB 없는 Store의 DLT. 코어에 의존하지 않는다 |
| `messaging-rabbitmq` | RabbitMQ adapter(classic queue). 코어에만 의존 |
| `messaging-rabbitmq-streams` | RabbitMQ Streams adapter(토픽마다 super stream, 분할마다 단일 활성 소비자, 결정 211). 단일 노드 전용(설정 주소로만 붙음). 코어에만 의존 |
| `messaging-redis` | Redis Streams adapter. 코어에만 의존 |
| `messaging-direct` | 브로커 없음 기준선(소비자가 Outbox 행을 직접 읽음). 코어에만 의존 |
| `test` | 컨테이너 없는 테스트 지원 |
| `testcontainers` | 통합 테스트용 실제 인프라 |
| `archunit` | 공통 코드의 독립성, 의존성 방향과 업무 계약 경계 검증 |

## 실행 환경과 정책

Servlet 앱은 Web·Validation·OpenAPI starter를 명시적으로 선택한다. Gateway는 자신의
WebFlux 설정을 사용한다. `web`의 응답·오류 계약을 가져오는 것만으로 Servlet 서버를
추가하지 않는다. MVC·OpenAPI·Security 예외 처리의 자동 구성은 해당 실행 환경과
클래스가 있을 때만 등록한다.

앱은 adapter 넷을 모두 `runtimeOnly`로 넣고 `stove.messaging.broker`(`kafka` 기본, `rabbitmq`, `redis-streams`, `direct`)로 고른다. 운영 코드는 `event`·`contract`·`messaging`만 컴파일 대상으로 보고 adapter를 모른다. 같은 산출물에서 설정만 바꿔 기술을 비교하기 위해서다(결정 209). 고른 브로커로 받을 수 없는 Store를 위해 일부 토픽(`also-kafka-topics`)은 `MirrorPublisher`(Kafka 구현)로도 보낸다.

Kafka 소비 기본 정책은 Kafka 실행 구성이 있을 때 등록한다. 이벤트 계약만 쓰는 실행에는
KafkaTemplate 빈을 요구하지 않는다. 격리·Outbox 운영 API와 Store의 DLT 운영 API는 Servlet 환경과 공통 Web
계약이 있고 `stove.messaging.ops.enabled`가 허용할 때만 등록한다.

`messaging`은 `jpa`·`event`·`contract`를 의존한다. DB 없는 Store는 `contract`만 컴파일하고 `messaging-kafka`를 런타임에 넣는다. `jpa`와 `security`는 `web`을 요구하지 않는다.

## 오류와 인증 계약

`web`은 오류 코드·HTTP 상태·기본 메시지의 계약과 일반 오류를 제공한다. 업무 오류는
해당 앱의 `core.domain`에서 정의한다. 업무 계층에서 공통 오류 계약과 BusinessException을
사용하는 의존성은 허용한다. 응답 형식과 기존 업무 오류 코드·상태는 유지한다.

Auth의 `sub`는 인증 주체, `member_id`는 숫자 회원 ID다. Studio의 workspace ID는 Studio가
subject에서 조회한다. 세 식별자를 서로 대입하지 않는다. 업무 API의 JWT는 서명·시간·issuer와
`esd-api` audience를 검증한다. 경로별 역할은 앱의 보안 설정에서, 자원 소유권은 업무 서비스에서
확인한다. 식별 정보 주입은 DB 조회나 업무 실행을 수행하지 않는다.

## 이벤트 계약

`contract`(`com.stove.common.contract`)에는 이벤트 인터페이스·타입·토픽·payload와 봉투·헤더 이름을 둔다. 계약은 JPA 엔티티와
앱 구현을 참조하지 않는다. 각 업무 서비스가 자신이 발행하는 계약의 의미와 변경을 책임진다.
이번 통합에서는 JSON 필드, topic, partition key, event type과 헤더 형식을 유지한다.
업무 payload의 재설계는 발행·소비 서비스의 재정비 시 함께 검증한다.

## 적용과 검증

1. 모듈 병합과 모든 소스·빌드·문서 참조를 이관한다.
2. 오류 계약을 확장 가능하게 만들고 기존 업무 오류를 앱으로 옮긴다.
3. 인증 지원과 선택적 자동 구성을 정리한다.
4. 공통 계약·자동 구성 테스트, 전체 단위 테스트와 통합 소스 컴파일을 수행한다.
5. 공통 런타임 의존성 방향과 auth·gateway의 회귀를 검증한다.

`./gradlew verifyCommonRuntimeDependencies`는 공통 라이브러리가 Servlet 서버·MVC를
런타임에 전파하지 않고, Kafka adapter가 JPA·메시징 코어를 요구하지 않는지, 코어·계약·포트와 다른 adapter의 런타임 클래스패스에 브로커 클라이언트(그룹 `org.apache.kafka`·`org.springframework.kafka`, `org.springframework.amqp`·`com.rabbitmq`, `io.lettuce`·`redis.clients`·`org.redisson`·모듈 `spring-data-redis`)가 없는지, adapter끼리 의존하지 않는지(결정 209) 검사한다. ArchUnit `CommonLibraryRules`(코어·계약·adapter)와 `MessagingBoundaryRules`(앱)가 같은 경계를 코드에서 다시 본다. 이 검증은
루트 `check`에도 포함된다.

테스트 지원과 컨테이너 지원의 분리는 유지한다. 패키지 규칙은 새 계약 위치를 반영하고,
공통 모듈이 앱을 참조하거나 하위 기술 모듈이 웹 실행 환경을 요구하는 변경을 검증한다.
MinIO 통합 테스트의 기본 이미지는 고정 소스의 로컬 Jib 이미지이며 필요한 테스트가
Gradle 의존성으로 자동 준비한다. `STOVE_TEST_MINIO_IMAGE`로 사전 공급 이미지를 선택할 수 있다.
기본 이미지의 릴리즈와 저장소 통합 검증 내용은 유지한다.
