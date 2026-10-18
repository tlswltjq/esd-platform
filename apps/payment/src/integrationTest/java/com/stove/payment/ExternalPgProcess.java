package com.stove.payment;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.MySQLContainer;

/** A real supplier process and separate MySQL; no provider classes enter Payment's classpath. */
final class ExternalPgProcess {
    static final String PAYMENT_KEY="payment-integration-key";
    static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mock_pg").withUsername("mock_pg").withPassword("pg-integration-password");
    static final int PORT;
    static final JdbcTemplate JDBC;
    private static Process process;
    static {
        try {
            MYSQL.start();
            JDBC=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()));
            try(ServerSocket socket=new ServerSocket(0)) { PORT=socket.getLocalPort(); }
            restart("APPROVED",300);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { stop(); MYSQL.stop(); }));
        } catch(Exception failure) { stop(); MYSQL.stop(); throw new ExceptionInInitializerError(failure); }
    }
    static void restart(String decision,int rate) {
        stop();
        try {
            ProcessBuilder builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx128m","-jar",
                    System.getProperty("mock.pg.jar"),"--server.port="+PORT);
            builder.environment().put("MOCK_PG_DB_HOST",MYSQL.getHost());
            builder.environment().put("MOCK_PG_DB_PORT",MYSQL.getMappedPort(3306).toString());
            builder.environment().put("MOCK_PG_DB_USER",MYSQL.getUsername()); builder.environment().put("MOCK_PG_DB_PASSWORD",MYSQL.getPassword());
            builder.environment().put("MOCK_PG_PAYMENT_KEY",PAYMENT_KEY);
            builder.environment().put("MOCK_PG_SETTLEMENT_KEY","settlement-integration-key");
            builder.environment().put("MOCK_PG_CONTROL_KEY","control-integration-key");
            builder.environment().put("MOCK_PG_DECISION",decision); builder.environment().put("MOCK_PG_FEE_RATE_BPS",Integer.toString(rate));
            builder.environment().put("MOCK_PG_FEE_POLICY_ID",rate==300 ? "SIMULATED_FEE_V1" : "SIMULATED_FEE_TEST_"+rate);
            builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(Path.of("build/mock-pg-process.log").toFile()));
            process=builder.start();
            SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(1));factory.setReadTimeout(Duration.ofSeconds(2));
            RestClient health=RestClient.builder().baseUrl(baseUrl()).requestFactory(factory).build();
            long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
            // Poll on this thread: another thread would wait on this class's initialization lock.
            while(true) {
                if(!process.isAlive()) throw new IllegalStateException("PG process stopped; see build/mock-pg-process.log");
                try { if(health.get().uri("/actuator/health").retrieve().toBodilessEntity().getStatusCode().is2xxSuccessful()) break; }
                catch(RuntimeException starting) { }
                if(System.nanoTime()>deadline) throw new IllegalStateException("PG health timed out");
                Thread.sleep(100);
            }
        } catch(Exception failure) { throw new IllegalStateException("Cannot launch independent PG",failure); }
    }
    static String baseUrl() { return "http://127.0.0.1:"+PORT; }
    static void stop() {
        if(process==null) return;
        process.destroy();
        try { if(!process.waitFor(15,TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS); } }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        process=null;
    }
    private ExternalPgProcess() { }
}
