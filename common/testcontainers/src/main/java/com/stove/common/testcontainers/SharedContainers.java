package com.stove.common.testcontainers;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.HostConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.ContainerState;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트용 인프라 컨테이너.
 *
 * <p>정적 싱글턴이라 같은 JVM(= 같은 Gradle 모듈) 안에서는 한 번만 뜬다.
 * 이미지 태그는 {@code docker-compose.yml} 과 맞춰 두었다 — 로컬에 이미 받아둔 이미지를
 * 그대로 쓰고, 테스트와 로컬 실행이 같은 버전을 보게 하기 위함이다.
 *
 * <p>컨테이너를 모듈 간에 재사용하지 않는다. 재사용하면 앞선 모듈이 남긴
 * {@code flyway_schema_history} 때문에 다음 모듈의 마이그레이션 검증이 깨진다 —
 * 모듈마다 깨끗한 인프라에서 시작하는 편이 검증으로서도 정확하다.
 *
 * <p>모든 컨테이너는 {@link #STARTUP_TIMEOUT}까지 시작을 기다린다(D-043). 기본값(로그·포트 60초, JDBC 120초)은
 * 부하가 몰린 CI 러너에서 모자라, 컨테이너 하나가 늦으면 그 모듈의 통합 테스트 컨텍스트가 모두 실패했다.
 * 대기는 실패할 때만 길어지고, 정상 시작(보통 10초 안)에는 영향이 없다.
 */
public final class SharedContainers {

    static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);

    /** Confluent Kafka(KRaft)가 준비되면 내는 로그. 라이브러리의 기본 대기 조건과 같다. */
    static final String KAFKA_READY_LOG = ".*Transitioning from RECOVERY to RUNNING.*";

    /** D-043: Kafka 준비 로그를 {@link #STARTUP_TIMEOUT}까지 기다리는 새 대기 객체. 재현 테스트가 같은 객체로 검증한다. */
    static WaitStrategy kafkaReady() {
        return Wait.forLogMessage(KAFKA_READY_LOG, 1).withStartupTimeout(STARTUP_TIMEOUT);
    }

    /**
     * Testcontainers의 기본 my.cnf는 {@code max_allowed_packet = 1M}이라 {@code docker-compose.yml}의 MySQL 8 기본값(64MB)과
     * 다르다. 같은 값으로 맞춰, 운영에서는 저장되는 큰 행(예: Payment 형식 거절 type 최대 60,000,000바이트)이 테스트에서만
     * 실패하거나, 반대로 테스트가 운영 한도를 넘는 값을 모르고 통과하지 않게 한다.
     */
    public static final MySQLContainer<?> MYSQL = limitMemoryForS3(
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withCommand("--max-allowed-packet=67108864")
                    .withStartupTimeoutSeconds((int) STARTUP_TIMEOUT.toSeconds()));

    /**
     * 로컬 실행은 {@code apache/kafka:3.9.0} 을 쓰지만 테스트는 Confluent 이미지를 쓴다.
     *
     * <p>Testcontainers 1.21 의 {@code kafka.KafkaContainer} 와 {@code apache/kafka:3.9.0} 조합에서는
     * 이미지 엔트리포인트가 광고 리스너를 주입받기 전에 스토리지를 포맷하면서
     * {@code advertised.listeners cannot use the nonroutable meta-address 0.0.0.0} 으로 죽는다.
     * 컨텍스트 로딩 검증에 브로커 구현 차이는 영향이 없으므로 안정적인 쪽을 택했다.
     *
     * <p>{@code ConfluentKafkaContainer}는 모든 인스턴스가 정적 {@code KafkaHelper.WAIT_STRATEGY}를 나눠 쓴다.
     * {@code withStartupTimeout}만 걸면 같은 JVM의 다른 Kafka 컨테이너까지 바뀌므로, 같은 로그 조건으로
     * 새 대기 객체를 준다.
     */
    public static final ConfluentKafkaContainer KAFKA = s3Kafka(
            new AtomicStarterKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.0"))
                    .waitingFor(kafkaReady()));

    public static final GenericContainer<?> REDIS = s3Redis(
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    // 새 대기 객체를 쓴다. withStartupTimeout만 걸면 GenericContainer가 모든 컨테이너와
                    // 나눠 쓰는 기본 대기 객체(DEFAULT_WAIT_STRATEGY)의 시간이 바뀐다.
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(STARTUP_TIMEOUT)));

    public static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer(
                    DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.18.6"))
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                    .withStartupTimeout(STARTUP_TIMEOUT);

    public static final MongoDBContainer MONGO =
            new MongoDBContainer(DockerImageName.parse("mongo:7"))
                    .withStartupTimeout(STARTUP_TIMEOUT);

    /** 결정 222: S3 측정을 켜는 시스템 속성. 루트 build.gradle 이 {@code -Pstove.contract.s3=true} 를 넘긴다. */
    public static final String S3_PROPERTY = "stove.contract.s3";

    /** 결정 222: S3 측정 때 브로커(브로커 없음은 MySQL) 컨테이너에 거는 메모리 한도. 모든 방식이 같다. */
    public static final long S3_MEMORY_LIMIT_BYTES = 1024L * 1024 * 1024;

    /**
     * 결정 222: S3 측정 때 Redis {@code maxmemory}. 기본 512mb. 한도 도달 뒤 재개 경로를 짧게 재현할 때만
     * {@code -Pstove.contract.s3.redis-maxmemory=8mb} 처럼 줄인다(PR #242 R1).
     */
    public static final String S3_REDIS_MAXMEMORY_PROPERTY = "stove.contract.s3.redis-maxmemory";

    /**
     * 결정 222: S3 측정 때 RabbitMQ·Streams 메모리 경보({@code vm_memory_high_watermark.relative}). 기본 0.4(한도의 40%).
     * RabbitMQ 4.1 기본값은 0.6 이라 명시한다(PR #242 R3). 생산자 차단 경로를 짧게 재현할 때만 줄인다.
     */
    public static final String S3_RABBIT_WATERMARK_PROPERTY = "stove.contract.s3.rabbit-watermark";

    /** S3 때 RabbitMQ 컨테이너에 넣는 설정 파일. 이미지의 {@code conf.d} 는 이름 순으로 읽혀 기본값 파일 뒤에 온다. */
    private static final String RABBIT_S3_CONF = "/etc/rabbitmq/conf.d/90-s3-memory.conf";

    private SharedContainers() {
    }

    /** S3 측정 중인가. 정적 컨테이너는 시작 전에(클래스 초기화 때) 이 값을 본다 — 시스템 속성은 그 전에 들어와 있다. */
    public static boolean s3Measurement() {
        return Boolean.getBoolean(S3_PROPERTY);
    }

    /**
     * S3 측정 때만 컨테이너 메모리를 {@link #S3_MEMORY_LIMIT_BYTES} 로 묶고 swap 을 막는다(memory-swap = memory).
     * 평소에는 아무것도 바꾸지 않는다 — 다른 서비스가 같은 공유 컨테이너를 쓴다.
     */
    public static <T extends GenericContainer<?>> T limitMemoryForS3(T container) {
        if (s3Measurement()) {
            container.withCreateContainerCmdModifier(SharedContainers::limitMemory);
        }
        return container;
    }

    private static void limitMemory(CreateContainerCmd command) {
        HostConfig hostConfig = command.getHostConfig() == null ? HostConfig.newHostConfig() : command.getHostConfig();
        command.withHostConfig(hostConfig.withMemory(S3_MEMORY_LIMIT_BYTES).withMemorySwap(S3_MEMORY_LIMIT_BYTES));
    }

    /** S3: Kafka 는 한도 1GB 안에서 힙 512MB(나머지는 페이지 캐시·JVM 밖 메모리). */
    private static ConfluentKafkaContainer s3Kafka(ConfluentKafkaContainer container) {
        if (s3Measurement()) {
            container.withEnv("KAFKA_HEAP_OPTS", "-Xmx512m -Xms512m");
        }
        return limitMemoryForS3(container);
    }

    /**
     * S3: Redis 는 {@code maxmemory 512mb}·{@code noeviction}(넘치면 쓰기를 거절하고 지우지 않는다)·AOF {@code everysec}
     * (S6·S7과 같은 영속).
     */
    private static GenericContainer<?> s3Redis(GenericContainer<?> container) {
        if (s3Measurement()) {
            container.withCommand("redis-server", "--maxmemory", System.getProperty(S3_REDIS_MAXMEMORY_PROPERTY, "512mb"),
                    "--maxmemory-policy", "noeviction", "--appendonly", "yes", "--appendfsync", "everysec");
        }
        return limitMemoryForS3(container);
    }

    /**
     * S3: RabbitMQ·Streams 컨테이너에 메모리 1GB 를 걸고 메모리 경보를 한도의 40%({@code vm_memory_high_watermark.relative = 0.4})로
     * 명시한다. 이 호스트(cgroup v1)에서 RabbitMQ 4.1 은 컨테이너 한도가 아니라 호스트 전체 메모리를 읽으므로(확인: 25GB, 경보 약 10GB),
     * 전체 메모리를 {@code total_memory_available_override_value} 로 컨테이너 한도와 같게 둔다. 평소에는 아무것도 바꾸지 않는다.
     */
    public static <T extends GenericContainer<?>> T rabbitForS3(T container) {
        if (s3Measurement()) {
            String watermark = System.getProperty(S3_RABBIT_WATERMARK_PROPERTY, "0.4");
            String conf = "total_memory_available_override_value = " + S3_MEMORY_LIMIT_BYTES + "\n"
                    + "vm_memory_high_watermark.relative = " + watermark + "\n";
            container.withCopyToContainer(Transferable.of(conf), RABBIT_S3_CONF);
        }
        return limitMemoryForS3(container);
    }

    /**
     * S3: 실행 중인 RabbitMQ 가 실제로 쓰는 메모리 경보 값과 그 값으로 계산한 절대 한도(바이트)를 읽는다
     * ({@code rabbitmqctl eval}). 설정 파일이 먹었는지 결과에 남기려는 것이다.
     */
    public static Map<String, String> rabbitMemorySettings(ContainerState rabbit) throws IOException, InterruptedException {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("vm_memory_high_watermark", rabbitEval(rabbit, "vm_memory_monitor:get_vm_memory_high_watermark()."));
        settings.put("vm_memory_limit_bytes", rabbitEval(rabbit, "vm_memory_monitor:get_memory_limit()."));
        settings.put("total_memory_bytes", rabbitEval(rabbit, "vm_memory_monitor:get_total_memory()."));
        return settings;
    }

    private static String rabbitEval(ContainerState rabbit, String expression) throws IOException, InterruptedException {
        Container.ExecResult result = rabbit.execInContainer("rabbitmqctl", "eval", expression);
        return result.getExitCode() == 0 ? result.getStdout().trim() : "error: " + result.getStderr().trim();
    }
}
