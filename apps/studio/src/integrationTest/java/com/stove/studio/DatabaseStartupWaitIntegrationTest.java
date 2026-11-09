package com.stove.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.stove.common.testcontainers.SharedContainers;
import com.zaxxer.hikari.HikariDataSource;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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

    @Test
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
        dataSource.setJdbcUrl("jdbc:mysql://127.0.0.1:" + port + "/" + MYSQL.getDatabaseName()
                + "?useSSL=false&allowPublicKeyRetrieval=true");
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
        private final int port;
        private final long createdAt = System.nanoTime();
        private final AtomicLong openedAt = new AtomicLong();
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private final Thread opener;
        private volatile ServerSocket server;

        private LateDatabasePort(int port, Duration delay, String targetHost, int targetPort) {
            this.port = port;
            opener = Thread.ofPlatform().daemon().name("late-database-port").start(() -> {
                try {
                    Thread.sleep(delay);
                    ServerSocket listening = new ServerSocket();
                    listening.setReuseAddress(true);
                    listening.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                    server = listening;
                    openedAt.set(System.nanoTime());
                    while (!listening.isClosed()) {
                        Socket client = listening.accept();
                        Socket target = new Socket(targetHost, targetPort);
                        sockets.add(client);
                        sockets.add(target);
                        pipe(client, target);
                        pipe(target, client);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException closed) {
                    // close()가 서버 소켓을 닫으면 accept가 끝난다.
                }
            });
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

        private static void pipe(Socket from, Socket to) {
            Thread.ofVirtual().start(() -> {
                try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                    in.transferTo(out);
                } catch (IOException closed) {
                    // 어느 한쪽이 닫히면 전달을 멈춘다.
                }
            });
        }

        @Override
        public void close() throws Exception {
            opener.interrupt();
            ServerSocket listening = server;
            if (listening != null) {
                listening.close();
            }
            for (Socket socket : sockets) {
                socket.close();
            }
            opener.join(Duration.ofSeconds(5).toMillis());
        }
    }
}
