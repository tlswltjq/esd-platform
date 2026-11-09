package com.stove.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.stove.common.testcontainers.SharedContainers;
import com.zaxxer.hikari.HikariDataSource;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.testcontainers.containers.MySQLContainer;

/**
 * 부팅 때 DB가 잠깐 닿지 않아도 첫 연결을 기다린다(결정 136).
 *
 * <p>Spring Boot는 {@code spring.datasource.hikari.*}를 {@code HikariDataSource}에 그대로 바인딩하고,
 * 부팅 중 Flyway가 그 데이터 소스에서 첫 연결을 받는다. 이 테스트는 Studio의 운영 설정 파일
 * ({@code src/main/resources/application.yml})의 같은 구간을 같은 방식으로 바인딩한 뒤, 처음
 * {@link #DATABASE_DOWN} 동안 연결을 거절하는 포트로 첫 연결을 받는다. Hikari 기본값(1ms)이면 첫
 * 시도에서 바로 실패하고, 결정 136의 {@code initialization-fail-timeout}이 있으면 포트가 열릴 때까지
 * 다시 시도해 연결을 받는다. 다른 8개 앱이 같은 값을 갖는지는
 * {@code common/test}의 {@code DatabaseStartupWaitContractTest}가 지킨다.
 */
class DatabaseStartupWaitIntegrationTest {

    private static final Duration DATABASE_DOWN = Duration.ofSeconds(4);

    private static final MySQLContainer<?> MYSQL = SharedContainers.MYSQL;

    @BeforeAll
    static void startDatabase() {
        MYSQL.start();
    }

    /** 재시도 예산 60초보다 길게 잡되, 드라이버나 전달 스레드가 멈춰도 테스트가 끝나게 따로 묶는다. */
    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void firstConnectionWaitsUntilTheDatabaseBecomesReachable() throws Exception {
        try (LateDatabasePort database = LateDatabasePort.opensAfter(DATABASE_DOWN, MYSQL.getHost(),
                     MYSQL.getMappedPort(MySQLContainer.MYSQL_PORT));
             HikariDataSource dataSource = studioDataSource(database.port())) {
            long started = System.nanoTime();

            assertThatCode(() -> {
                try (Connection connection = dataSource.getConnection();
                     Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery("SELECT 1")) {
                    assertThat(result.next()).isTrue();
                }
            }).as("DB가 %s 동안 닿지 않아도 부팅 중 첫 연결은 실패하지 않는다", DATABASE_DOWN)
                    .doesNotThrowAnyException();

            Duration waited = Duration.ofNanos(System.nanoTime() - started);
            assertThat(waited).as("포트가 열리기 전에는 연결을 받을 수 없다 — 실제로 기다렸다")
                    .isGreaterThanOrEqualTo(DATABASE_DOWN.minusMillis(500));
            assertThat(database.openedAfter()).as("포트가 열린 시각").isGreaterThanOrEqualTo(DATABASE_DOWN);
        }
    }

    /** Studio 운영 설정의 {@code spring.datasource.hikari} 구간을 Spring Boot와 같은 방식으로 바인딩한다. */
    private static HikariDataSource studioDataSource(int port) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("studio-application", new FileSystemResource(Path.of("src/main/resources/application.yml")));
        HikariDataSource dataSource = new HikariDataSource();
        new Binder(ConfigurationPropertySources.from(sources))
                .bind("spring.datasource.hikari", Bindable.ofInstance(dataSource));
        // connectTimeout·socketTimeout은 테스트가 드라이버 안에서 멈추지 않게 하는 테스트 전용 값이다(운영 URL에는 없다).
        dataSource.setJdbcUrl("jdbc:mysql://127.0.0.1:" + port + "/" + MYSQL.getDatabaseName()
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=10000");
        dataSource.setUsername(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        return dataSource;
    }

    /**
     * 처음에는 연결을 거절하고, 정해진 시간이 지나면 열려 MySQL로 바이트를 그대로 넘기는 포트.
     *
     * <p>포트를 미리 하나 골라 두되 열지 않으므로 그동안의 연결은 거절된다(DB가 아직 닿지 않는 상태).
     */
    private static final class LateDatabasePort implements AutoCloseable {
        private static final int TARGET_CONNECT_TIMEOUT_MILLIS = 3000;
        private static final Duration THREAD_STOP_WAIT = Duration.ofSeconds(5);

        private final int port;
        private final long createdAt = System.nanoTime();
        private final AtomicLong openedAt = new AtomicLong();
        /** 서버 소켓·클라이언트·대상 소켓. 만든 즉시 등록해 어느 경로로 끝나도 close()가 닫는다. */
        private final List<Closeable> resources = new ArrayList<>();
        private final List<Thread> pipes = new ArrayList<>();
        private final Thread opener;
        private boolean closed;

        private LateDatabasePort(int port, Duration delay, String targetHost, int targetPort) {
            this.port = port;
            opener = Thread.ofPlatform().daemon().name("late-database-port").start(() -> {
                try {
                    Thread.sleep(delay);
                    ServerSocket listening = own(new ServerSocket());
                    listening.setReuseAddress(true);
                    listening.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                    openedAt.set(System.nanoTime());
                    while (true) {
                        Socket client = own(listening.accept());
                        Socket target = own(new Socket());
                        try {
                            target.connect(new InetSocketAddress(targetHost, targetPort), TARGET_CONNECT_TIMEOUT_MILLIS);
                        } catch (IOException unreachable) {
                            // MySQL에 닿지 못하면 클라이언트를 바로 닫아 드라이버가 인사를 기다리며 멈추지 않게 한다.
                            client.close();
                            target.close();
                            continue;
                        }
                        pipe(client, target);
                        pipe(target, client);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException closedOrFailed) {
                    // close()가 서버 소켓을 닫으면 accept가 끝난다.
                }
            });
        }

        private synchronized <T extends Closeable> T own(T resource) throws IOException {
            if (closed) {
                resource.close();
                throw new IOException("이미 닫힌 포트");
            }
            resources.add(resource);
            return resource;
        }

        static LateDatabasePort opensAfter(Duration delay, String targetHost, int targetPort) throws IOException {
            try (ServerSocket probe = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
                return new LateDatabasePort(probe.getLocalPort(), delay, targetHost, targetPort);
            }
        }

        int port() {
            return port;
        }

        Duration openedAfter() {
            long opened = openedAt.get();
            return opened == 0 ? Duration.ZERO : Duration.ofNanos(opened - createdAt);
        }

        private synchronized void pipe(Socket from, Socket to) throws IOException {
            if (closed) {
                throw new IOException("이미 닫힌 포트");
            }
            pipes.add(Thread.ofVirtual().start(() -> {
                try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                    in.transferTo(out);
                } catch (IOException socketClosed) {
                    // 어느 한쪽이 닫히면 전달을 멈춘다.
                }
            }));
        }

        @Override
        public void close() throws Exception {
            List<Closeable> owned;
            List<Thread> running;
            synchronized (this) {
                closed = true;
                owned = List.copyOf(resources);
                running = List.copyOf(pipes);
            }
            opener.interrupt();
            for (Closeable resource : owned) {
                try {
                    resource.close();
                } catch (IOException alreadyBroken) {
                    // 닫는 중 실패해도 나머지는 닫는다.
                }
            }
            opener.join(THREAD_STOP_WAIT);
            for (Thread pipe : running) {
                pipe.join(THREAD_STOP_WAIT);
            }
            if (opener.isAlive() || running.stream().anyMatch(Thread::isAlive)) {
                throw new IllegalStateException("늦게 열리는 포트의 스레드가 끝나지 않았다");
            }
        }
    }
}
