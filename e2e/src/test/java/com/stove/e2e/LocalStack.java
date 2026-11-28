package com.stove.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stove.common.testcontainers.InfraContainers;
import com.stove.common.testcontainers.SharedContainers;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트가 직접 띄우고 제어하는 일회용 스택(결정 e2e-stack-in-junit).
 *
 * <p>인프라(MySQL·Redis·Kafka·MinIO)는 Testcontainers가 띄우고, 앱은 {@code bootJar}를 이 호스트의 Java 프로세스로
 * 띄운다. 앱을 컨테이너에 넣지 않는 이유는 발급자 주소다 — Auth의 발급자와 BFF의 공개 주소는 브라우저(이 테스트)와
 * 다른 앱이 <b>같은 문자열</b>로 닿아야 하는데, 컨테이너 네트워크 안과 밖에서 같은 주소가 되려면 호스트 포트를 쓰는
 * 편이 단순하다. 지금까지 {@code tools/verification/verify-*.py}가 쓰던 방식과 같다.
 *
 * <p>요청은 여전히 스택 <b>밖에서</b> BFF·Gateway로 보낸다({@link Stove}). 이 클래스가 쥐는 것은 수명(기동·정지·
 * 재시작)뿐이다.
 */
final class LocalStack implements AutoCloseable {

    /** 예전 {@code verify-gateway-ownership.py}가 쓰던 자리(결정 105의 도구별 1000폭 구간). */
    static final int DEFAULT_PORT_OFFSET = 15_000;

    private static final int EPHEMERAL_START = 32_768;
    private static final String DB_USER = "stove";
    private static final String DB_PASSWORD = "stove1234";
    private static final String STORAGE_USER = "stove";
    private static final String STORAGE_PASSWORD = "stove1234";
    private static final String READER_PASSWORD = "isolated-read-only-password";
    private static final String BUCKET = "stove-builds";
    private static final List<String> SCHEMAS = List.of("auth", "studio", "review", "catalog", "store", "order",
            "payment", "license", "settlement");
    private static final Duration APP_STOP_LIMIT = Duration.ofSeconds(40);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private final Path repository;
    private final Path directory;
    private final int offset;
    private final MySQLContainer<?> mysql;
    private final GenericContainer<?> redis;
    private final ConfluentKafkaContainer kafka;
    private final GenericContainer<?> minio;
    private final Map<String, String> environment = new LinkedHashMap<>();
    private final Set<StackApp> apps;
    private final Map<StackApp, Process> processes = new EnumMap<>(StackApp.class);
    private final Map<String, Set<String>> credentialRecipients = new TreeMap<>();
    private final String settlementServiceKey = secret();
    private boolean kafkaPaused;
    private final Thread shutdownHook = new Thread(this::stopProcesses, "local-stack-shutdown");

    private LocalStack(Path repository, Path directory, int offset, Set<StackApp> apps) {
        this.apps = apps;
        this.repository = repository;
        this.directory = directory;
        this.offset = offset;
        this.mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                .withUsername(DB_USER)
                .withPassword(DB_PASSWORD)
                // 통합 테스트의 MySQL과 같은 한도·대기 시간이다(SharedContainers.MYSQL).
                .withCommand("--max-allowed-packet=67108864")
                .withStartupTimeoutSeconds(180)
                .withCopyToContainer(Transferable.of(schemaScript()), "/docker-entrypoint-initdb.d/01-stove.sql");
        // Redis·Kafka는 통합 테스트가 쓰는 정의를 그대로 쓴다. apache/kafka 이미지는 Testcontainers와 맞지 않아
        // Confluent 이미지를 쓰는 이유가 SharedContainers.KAFKA에 적혀 있다. 테스트 JVM 하나에 스택은 하나다.
        this.redis = SharedContainers.REDIS;
        this.kafka = SharedContainers.KAFKA;
        this.minio = new GenericContainer<>(InfraContainers.minioImage())
                .withEnv("MINIO_ROOT_USER", STORAGE_USER)
                .withEnv("MINIO_ROOT_PASSWORD", STORAGE_PASSWORD)
                .withCommand("server", "/data")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    }

    /**
     * 고른 앱을 띄운다. 실패하면 띄운 것을 내리고 던진다. 인프라는 고른 앱과 상관없이 모두 뜬다.
     *
     * <p>{@link Stove}가 클래스 초기화 때 읽는 {@code stove.e2e.port-offset}을 여기서 정한다. 그래서 {@link Stove}를
     * 처음 건드리기 전에 불려야 한다({@link LocalStackExtension}).
     */
    static LocalStack start(Set<StackApp> apps) {
        Path repository = Path.of(required("stove.e2e.repo-root")).toAbsolutePath();
        int offset = portOffset();
        System.setProperty("stove.e2e.port-offset", Integer.toString(offset));
        if (apps.isEmpty()) {
            throw new IllegalArgumentException("띄울 앱이 없다");
        }
        String run = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        Path directory = runDirectory(repository.resolve("e2e/build/local-stack"), run);
        LocalStack stack = new LocalStack(repository, directory, offset, EnumSet.copyOf(apps));
        try {
            Instant began = Instant.now();
            stack.startInfrastructure();
            // Kafka에 직접 쓰는 여정(ReviewCreatorFlowTest 등)이 읽는 주소다.
            System.setProperty("stove.e2e.kafka-brokers", stack.kafkaBootstrapServers());
            Instant infrastructureReady = Instant.now();
            stack.startApplications();
            // 이 층은 판정만이 아니라 숫자를 남긴다(e2e/build.gradle의 showStandardStreams와 같은 이유).
            System.out.printf("  LocalStack 기동: 인프라 %d초 + 앱 %d개 %d초 (오프셋 %d, 로그 %s)%n",
                    Duration.between(began, infrastructureReady).toSeconds(), apps.size(),
                    Duration.between(infrastructureReady, Instant.now()).toSeconds(), offset, directory);
            return stack;
        } catch (Exception failure) {
            stack.close();
            throw new IllegalStateException("스택을 띄우지 못했다. 앱 로그: " + directory, failure);
        }
    }

    /** 앱 로그와 증거 파일이 남는 디렉터리. */
    Path directory() {
        return directory;
    }

    String kafkaBootstrapServers() {
        return kafka.getBootstrapServers();
    }

    /** 앱 하나를 멈춘다. 이미 멈췄으면 아무 일도 하지 않는다. */
    void stop(StackApp app) {
        Process process = processes.remove(app);
        if (process != null) {
            terminate(process);
        }
    }

    /** 떠 있는 앱 집합. */
    Set<StackApp> apps() {
        return Set.copyOf(apps);
    }

    /**
     * 멈춘 앱을 다시 띄우고 준비될 때까지 기다린다. {@code arguments}는 이번 기동에만 붙는 실행 인자다(예: {@code
     * --stove.outbox.relay-enabled=false}).
     */
    void start(StackApp app, String... arguments) {
        if (!apps.contains(app)) {
            throw new IllegalArgumentException(app.module() + " 은 이 스택에 없다: " + apps);
        }
        launch(app, arguments);
        awaitReady(List.of(app), Duration.ofSeconds(180));
    }

    /**
     * 멈춘 앱을 띄우기만 하고 준비를 기다리지 않는다. 의존 대상이 죽어 있는 동안의 동작을 볼 때 쓰고, 뒤에
     * {@link #awaitReady(StackApp)}로 기다린다.
     */
    void startWithoutWaiting(StackApp app, String... arguments) {
        if (!apps.contains(app)) {
            throw new IllegalArgumentException(app.module() + " 은 이 스택에 없다: " + apps);
        }
        launch(app, arguments);
    }

    void awaitReady(StackApp app) {
        awaitReady(List.of(app), Duration.ofSeconds(180));
    }

    /** 이 실행에서 앱이 지금까지 남긴 로그(재시작해도 이어 쓴다). */
    String log(StackApp app) {
        try {
            return Files.readString(directory.resolve(app.module() + ".log"));
        } catch (IOException failure) {
            throw new IllegalStateException(app.module() + " 로그를 읽지 못했다", failure);
        }
    }

    /**
     * 브로커를 무응답으로 만든다. 컨테이너를 멈추지 않고 얼린다 — 멈췄다 다시 띄우면 호스트 포트가 바뀌어 앱이 아는
     * 주소로 돌아오지 않는다. 연결은 맺어지지만 응답이 없으므로 앱 쪽에서는 제한 시간 초과로 보인다.
     */
    void pauseKafka() {
        kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
        kafkaPaused = true;
    }

    void resumeKafka() {
        if (kafkaPaused) {
            kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
            kafkaPaused = false;
        }
    }

    /** 앱 프로세스를 실제로 내렸다가 다시 띄운다. 인자를 주지 않으면 기본 설정으로 뜬다. */
    void restart(StackApp app, String... arguments) {
        stop(app);
        start(app, arguments);
    }

    /** 스택의 MySQL에서 한 줄짜리 조회를 한다. 탭으로 나뉜 값이 돌아온다. */
    String sql(String query) {
        try {
            Container.ExecResult result = mysql.execInContainer(
                    "mysql", "-u" + DB_USER, "-p" + DB_PASSWORD, "-N", "-B", "-e", query);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("조회 실패: " + result.getStderr());
            }
            return result.getStdout().strip();
        } catch (IOException | InterruptedException failure) {
            throw new IllegalStateException("조회를 실행하지 못했다", failure);
        }
    }

    @Override
    public void close() {
        stopProcesses();
        resumeKafka();
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException alreadyShuttingDown) {
            // JVM이 내려가는 중이면 훅이 이미 돌고 있다.
        }
        Stream.of(minio, kafka, redis, mysql).forEach(GenericContainer::stop);
    }

    private void startInfrastructure() throws IOException, InterruptedException {
        Startables.deepStart(mysql, redis, kafka, minio).join();
        mc("alias", "set", "admin", "http://localhost:9000", STORAGE_USER, STORAGE_PASSWORD);
        mc("mb", "admin/" + BUCKET);
        // Review·Download·Catalog 검증기는 읽기 전용 계정으로만 빌드 저장소에 닿는다.
        minio.copyFileToContainer(Transferable.of("""
                {"Version":"2012-10-17","Statement":[
                 {"Effect":"Allow","Action":["s3:GetObject"],"Resource":["arn:aws:s3:::%s/*"]},
                 {"Effect":"Allow","Action":["s3:ListBucket"],"Resource":["arn:aws:s3:::%s"]}]}
                """.formatted(BUCKET, BUCKET)), "/tmp/build-read-policy.json");
        mc("admin", "policy", "create", "admin", "build-read", "/tmp/build-read-policy.json");
        for (String reader : List.of("review-reader", "download-reader")) {
            mc("admin", "user", "add", "admin", reader, READER_PASSWORD);
            mc("admin", "policy", "attach", "admin", "build-read", "--user", reader);
        }
        requireReadOnly("review-reader");
    }

    /**
     * 읽기 전용 계정이 정말 쓰지도 지우지도 못하는지 확인한다. 준비물이 잘못돼 계정에 쓰기 권한이 붙으면, Review가
     * 읽기 전용 계정으로만 빌드 저장소에 닿는다는 전제가 조용히 깨진 채 여정이 통과한다.
     */
    private void requireReadOnly(String reader) throws IOException, InterruptedException {
        String fixture = "admin/" + BUCKET + "/iam-check.bin";
        shell("printf 'read-only fixture' | mc pipe " + fixture);
        mc("alias", "set", "reader", "http://localhost:9000", reader, READER_PASSWORD);
        if (minio.execInContainer("sh", "-c", "printf forbidden | mc pipe reader/" + BUCKET + "/write-must-fail.bin")
                .getExitCode() == 0) {
            throw new IllegalStateException(reader + " 계정이 빌드 저장소에 쓸 수 있다");
        }
        if (minio.execInContainer("mc", "rm", "reader/" + BUCKET + "/iam-check.bin").getExitCode() == 0) {
            throw new IllegalStateException(reader + " 계정이 빌드 저장소에서 지울 수 있다");
        }
        mc("stat", fixture);
    }

    private void shell(String script) throws IOException, InterruptedException {
        Container.ExecResult result = minio.execInContainer("sh", "-c", script);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(script + " 실패: " + result.getStderr());
        }
    }

    private void mc(String... arguments) throws IOException, InterruptedException {
        String[] command = Stream.concat(Stream.of("mc"), Stream.of(arguments)).toArray(String[]::new);
        Container.ExecResult result = minio.execInContainer(command);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("mc " + arguments[0] + " 실패: " + result.getStderr());
        }
    }

    private void startApplications() {
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        environment.putAll(System.getenv());
        environment.putAll(baseEnvironment());
        // 기동 순서는 의존을 따른다: 발급자와 입구 → 공개 조회 사본 → 구매 → 다운로드 → 정산.
        launchAndAwait(Duration.ofSeconds(240), StackApp.AUTH, StackApp.STUDIO, StackApp.WEB_BFF, StackApp.GATEWAY,
                StackApp.REVIEW, StackApp.CATALOG);
        launchAndAwait(Duration.ofSeconds(180), StackApp.STORE);
        launchAndAwait(Duration.ofSeconds(180), StackApp.ORDER, StackApp.PAYMENT, StackApp.LICENSE);
        environment.put("LICENSE_BASE_URL", address(StackApp.LICENSE));
        environment.put("STORAGE_BUCKET", BUCKET);
        environment.put("DOWNLOAD_STORAGE_ACCESS_KEY", "download-reader");
        environment.put("DOWNLOAD_STORAGE_SECRET_KEY", READER_PASSWORD);
        environment.put("DOWNLOAD_BUILD_URL_TTL", "PT5M");
        launchAndAwait(Duration.ofSeconds(180), StackApp.DOWNLOAD);
        launchAndAwait(Duration.ofSeconds(180), StackApp.SETTLEMENT);
    }

    private Map<String, String> baseEnvironment() {
        String publicAddress = address(StackApp.WEB_BFF);
        String storage = "http://127.0.0.1:" + minio.getMappedPort(9000);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("SERVER_ADDRESS", "127.0.0.1");
        values.put("DB_HOST", "127.0.0.1");
        values.put("DB_PORT", Integer.toString(mysql.getMappedPort(3306)));
        values.put("DB_USER", DB_USER);
        values.put("DB_PASSWORD", DB_PASSWORD);
        values.put("REDIS_HOST", "127.0.0.1");
        values.put("REDIS_PORT", Integer.toString(redis.getMappedPort(6379)));
        values.put("KAFKA_BROKERS", kafkaBootstrapServers());
        values.put("CATALOG_STORE_SERVICE_KEY", secret());
        values.put("CATALOG_ORDER_SERVICE_KEY", secret());
        values.put("CATALOG_DOWNLOAD_SERVICE_KEY", secret());
        values.put("PAYMENT_ORDER_SERVICE_KEY", secret());
        values.put("AUTH_ISSUER", publicAddress);
        values.put("JWT_ISSUER_URI", publicAddress);
        values.put("JWT_JWK_SET_URI", address(StackApp.AUTH) + "/oauth2/jwks");
        values.put("BFF_CLIENT_SECRET", "isolated-e2e-bff-secret");
        values.put("BFF_PUBLIC_AUTH_BASE", publicAddress);
        values.put("BFF_REDIRECT_URI", publicAddress + "/login/oauth2/code/gateway-bff");
        values.put("STORAGE_ENDPOINT", storage);
        values.put("STORAGE_PRESIGN_ENDPOINT", storage);
        values.put("STORAGE_ACCESS_KEY", STORAGE_USER);
        values.put("STORAGE_SECRET_KEY", STORAGE_PASSWORD);
        values.put("STORAGE_ENCRYPTION_ENABLED", "false");
        values.put("STUDIO_ASSET_PUBLIC_BASE_URL", publicAddress);
        values.put("TRACING_SAMPLE_RATE", "0");
        values.put("AUTH_REVIEWER_EMAIL", ReviewJourney.REVIEWER_EMAIL);
        values.put("AUTH_REVIEWER_PASSWORD", ReviewJourney.REVIEWER_PASSWORD);
        values.put("AUTH_ADMIN_EMAIL", ReviewJourney.ADMIN_EMAIL);
        values.put("AUTH_ADMIN_PASSWORD", ReviewJourney.ADMIN_PASSWORD);
        values.put("REVIEW_STORAGE_ACCESS_KEY", "review-reader");
        values.put("REVIEW_STORAGE_SECRET_KEY", READER_PASSWORD);
        for (StackApp app : StackApp.values()) {
            values.put(app.uriVariable(), address(app));
        }
        values.put("API_GATEWAY_URI", address(StackApp.GATEWAY));
        values.put("CATALOG_BASE_URL", address(StackApp.CATALOG));
        return values;
    }

    private void launchAndAwait(Duration limit, StackApp... group) {
        List<StackApp> chosen = Stream.of(group).filter(apps::contains).toList();
        for (StackApp app : chosen) {
            launch(app);
        }
        awaitReady(chosen, limit);
    }

    private void launch(StackApp app, String... arguments) {
        Map<String, String> selected = new LinkedHashMap<>(ServiceCredentials.environmentFor(app, environment));
        if (app == StackApp.CATALOG || app == StackApp.SETTLEMENT) {
            selected.put("CATALOG_SETTLEMENT_SERVICE_KEY", settlementServiceKey);
        }
        credentialRecipients.put(app.module(), ServiceCredentials.credentialNames(selected));
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m", "-jar", applicationJar(app).toString(),
                "--server.address=127.0.0.1", "--server.port=" + (app.port() + offset)));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(repository.toFile());
        builder.environment().clear();
        builder.environment().putAll(selected);
        File log = directory.resolve(app.module() + ".log").toFile();
        builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log));
        try {
            JSON.writerWithDefaultPrettyPrinter()
                    .writeValue(directory.resolve("credential-recipients.json").toFile(), credentialRecipients);
            processes.put(app, builder.start());
        } catch (IOException failure) {
            throw new IllegalStateException(app.module() + " 을 띄우지 못했다", failure);
        }
    }

    private void awaitReady(List<StackApp> apps, Duration limit) {
        Instant deadline = Instant.now().plus(limit);
        List<StackApp> waiting = new ArrayList<>(apps);
        while (!waiting.isEmpty()) {
            for (StackApp app : apps) {
                Process process = processes.get(app);
                if (process == null || !process.isAlive()) {
                    throw new IllegalStateException(app.module() + " 이 멈췄다. 로그: "
                            + directory.resolve(app.module() + ".log"));
                }
            }
            waiting.removeIf(this::healthy);
            if (waiting.isEmpty()) {
                return;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("기동 시간 초과: " + waiting + ". 로그: " + directory);
            }
            sleep(Duration.ofSeconds(1));
        }
    }

    private boolean healthy(StackApp app) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(address(app) + "/actuator/health"))
                .timeout(Duration.ofSeconds(3)).GET().build();
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException notYet) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("기동 대기가 중단됐다", interrupted);
        }
    }

    private Path applicationJar(StackApp app) {
        Path libraries = repository.resolve("apps").resolve(app.module()).resolve("build/libs");
        try (Stream<Path> files = Files.list(libraries)) {
            List<Path> runnable = files
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> !path.getFileName().toString().endsWith("-plain.jar"))
                    .filter(path -> !path.getFileName().toString().endsWith("-test-fixtures.jar"))
                    .toList();
            if (runnable.size() != 1) {
                throw new IllegalStateException(app.module() + " 의 실행 JAR이 하나가 아니다: " + runnable);
            }
            return runnable.get(0);
        } catch (IOException failure) {
            throw new IllegalStateException(app.module() + " 의 bootJar가 없다(" + libraries + ")", failure);
        }
    }

    /** 이 스택에서 앱이 듣는 주소. Gateway를 거치지 않고 앱의 상태를 직접 볼 때 쓴다. */
    String address(StackApp app) {
        return "http://127.0.0.1:" + (app.port() + offset);
    }

    private synchronized void stopProcesses() {
        List<Process> running = new ArrayList<>(processes.values());
        processes.clear();
        running.forEach(Process::destroy);
        running.forEach(LocalStack::terminate);
    }

    private static void terminate(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(APP_STOP_LIMIT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor();
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /** 오프셋을 더한 앱 포트가 모두 비어 있고 임시 포트 범위 아래인지 확인한다(결정 105). */
    private static int portOffset() {
        String configured = System.getProperty("stove.e2e.port-offset", "");
        int offset = configured.isBlank() ? DEFAULT_PORT_OFFSET : Integer.parseInt(configured.strip());
        int[] ephemeral = hostEphemeralRange();
        for (StackApp app : StackApp.values()) {
            int port = app.port() + offset;
            if (port >= EPHEMERAL_START || (port >= ephemeral[0] && port <= ephemeral[1])) {
                throw new IllegalStateException("앱 포트 " + port + " 가 임시 포트 범위 안이다(이 호스트 "
                        + ephemeral[0] + "~" + ephemeral[1] + "). stove.e2e.port-offset을 바꾼다");
            }
            try (ServerSocket probe = new ServerSocket()) {
                probe.setReuseAddress(true);
                probe.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            } catch (IOException inUse) {
                throw new IllegalStateException("앱 포트 " + port + "(" + app.module() + ") 를 다른 프로세스가 쓰고 있다. "
                        + "그 프로세스를 멈추거나 stove.e2e.port-offset을 바꾼다", inUse);
            }
        }
        return offset;
    }

    /**
     * 이 호스트가 나가는 연결에 쓰는 포트 범위. 기본 시작값(32768)보다 낮게 잡힌 호스트에서는 검사 때 비어 있던 앱
     * 포트를 다른 연결이 먼저 가져갈 수 있다. 읽을 수 없으면(리눅스가 아니면) 기본 범위로 본다.
     */
    private static int[] hostEphemeralRange() {
        Path range = Path.of("/proc/sys/net/ipv4/ip_local_port_range");
        if (Files.isReadable(range)) {
            try {
                String[] bounds = Files.readString(range).strip().split("\\s+");
                return new int[] {Integer.parseInt(bounds[0]), Integer.parseInt(bounds[1])};
            } catch (IOException | RuntimeException unreadable) {
                // 아래 기본 범위로 본다.
            }
        }
        return new int[] {EPHEMERAL_START, 65_535};
    }

    /** 실행마다 고유한 디렉터리. 같은 초에 시작한 두 실행의 로그와 증거가 섞이지 않는다. */
    private static Path runDirectory(Path parent, String run) {
        try {
            Files.createDirectories(parent);
            return Files.createTempDirectory(parent, run + "-");
        } catch (IOException failure) {
            throw new IllegalStateException("실행 디렉터리를 만들지 못했다: " + parent, failure);
        }
    }

    private static String schemaScript() {
        StringBuilder script = new StringBuilder();
        for (String schema : SCHEMAS) {
            script.append("CREATE DATABASE stove_").append(schema).append(";\n");
            script.append("GRANT ALL PRIVILEGES ON stove_").append(schema).append(".* TO '")
                    .append(DB_USER).append("'@'%';\n");
        }
        return script.toString();
    }

    private static String secret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String required(String property) {
        String value = System.getProperty(property, "");
        if (value.isBlank()) {
            throw new IllegalStateException("-D" + property + " 가 필요하다(e2e/build.gradle의 localStack 태스크가 넘긴다)");
        }
        return value;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("대기가 중단됐다", interrupted);
        }
    }
}
