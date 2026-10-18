package com.stove.license;

import static com.stove.license.LicenseFixtures.paid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.testcontainers.SharedContainers;
import com.stove.license.core.domain.PaidOrder;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

class LicenseMigrationIntegrationTest extends LicenseIntegrationTestBase {
    private static final String LOCATION = "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath();

    @Test
    void v5CopiesAllFactsWithoutChangingIdsTimesInboxOrHistoricalSources() {
        withV4(source -> {
            JdbcTemplate jdbc = new JdbcTemplate(source);
            PaidOrder order = paid();
            seed(jdbc, order);
            Map<String, Object> right = jdbc.queryForMap("SELECT * FROM product_license");
            Map<String, Object> historical = jdbc.queryForMap("SELECT * FROM license_purchase");
            Map<String, Object> inbox = jdbc.queryForMap("SELECT * FROM processed_event");
            Flyway flyway = Flyway.configure().dataSource(source).locations(LOCATION).load();
            flyway.migrate(); flyway.validate();
            Map<String, Object> merged = jdbc.queryForMap("SELECT * FROM product_license");
            assertThat(merged).containsAllEntriesOf(right);
            HashMap<String, Object> facts = new HashMap<>(historical);
            facts.remove("id"); facts.remove("license_id");
            assertThat(merged).containsAllEntriesOf(facts);
            assertThat(jdbc.queryForMap("SELECT * FROM license_purchase")).isEqualTo(historical);
            assertThat(jdbc.queryForMap("SELECT * FROM processed_event")).isEqualTo(inbox);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"multiple", "missing", "product", "title", "inbox", "orphan", "type"})
    void v5RejectsLossyOrInconsistentDataBeforeAddingAnyBusinessColumns(String problem) {
        withV4(source -> {
            JdbcTemplate jdbc = new JdbcTemplate(source);
            seed(jdbc, paid());
            switch (problem) {
                case "multiple" -> source(jdbc, paid());
                case "missing" -> jdbc.update("DELETE FROM license_purchase");
                case "product" -> jdbc.update("UPDATE license_purchase SET product_id=2");
                case "title" -> jdbc.update("UPDATE license_purchase SET title='FIRST TITLE'");
                case "inbox" -> jdbc.update("DELETE FROM processed_event");
                case "orphan" -> jdbc.update("INSERT INTO processed_event(event_id,consumer_group,event_type,processed_at) VALUES('orphan','license-paid-order','OrderPaid',NOW())");
                case "type" -> jdbc.update("UPDATE processed_event SET event_type='OrderPlaced'");
            }
            List<Map<String, Object>> rights = jdbc.queryForList("SELECT * FROM product_license");
            List<Map<String, Object>> sources = jdbc.queryForList("SELECT * FROM license_purchase");
            assertThatThrownBy(() -> Flyway.configure().dataSource(source).locations(LOCATION).load().migrate())
                    .hasStackTraceContaining("chk_license_merge");
            assertThat(jdbc.queryForList("SELECT * FROM product_license")).isEqualTo(rights);
            assertThat(jdbc.queryForList("SELECT * FROM license_purchase")).isEqualTo(sources);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='product_license' AND column_name='event_id'", Integer.class)).isZero();
        });
    }

    private void seed(JdbcTemplate jdbc, PaidOrder order) {
        jdbc.update("INSERT INTO product_license(id,license_id,buyer_subject,product_id,product_code,title,acquired_at) VALUES(41,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), order.buyerSubject(), order.productId(), order.productCode(), order.title(),
                Timestamp.from(Instant.parse("2026-10-17T01:03:00.123456Z")));
        source(jdbc, order);
    }

    private void source(JdbcTemplate jdbc, PaidOrder order) {
        jdbc.update("INSERT INTO license_purchase(license_id,event_id,occurred_at,order_no,request_event_id,payment_result_event_id,payment_id,buyer_subject,product_id,product_code,title,content_version,amount,currency,received_at) VALUES(41,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                order.eventId(), Timestamp.from(order.occurredAt()), order.orderNo(), order.requestEventId(), order.paymentResultEventId(), order.paymentId(),
                order.buyerSubject(), order.productId(), order.productCode(), order.title(), order.contentVersion(), order.amount(), order.currency(),
                Timestamp.from(Instant.parse("2026-10-17T01:03:00.654321Z")));
        jdbc.update("INSERT INTO processed_event(event_id,consumer_group,event_type,processed_at) VALUES(?,'license-paid-order','OrderPaid',NOW())", order.eventId());
    }

    private void withV4(Consumer<DriverManagerDataSource> check) {
        MySQLContainer<? extends MySQLContainer<?>> mysql = SharedContainers.MYSQL;
        String schema = "license_merge_" + UUID.randomUUID().toString().replace("-", "");
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(server + mysql.getDatabaseName()
                + "?useSSL=false&allowPublicKeyRetrieval=true", "root", mysql.getPassword()));
        root.execute("CREATE DATABASE " + schema);
        try {
            DriverManagerDataSource source = new DriverManagerDataSource(server + schema + "?useSSL=false&allowPublicKeyRetrieval=true", "root", mysql.getPassword());
            Flyway.configure().dataSource(source).locations(LOCATION).target("4").load().migrate();
            check.accept(source);
        } finally { root.execute("DROP DATABASE " + schema); }
    }

    @Test
    void v4PreservesLegacyRightsInboxAndPendingOutboxWithoutConvertingThem() {
        MySQLContainer<? extends MySQLContainer<?>> mysql = SharedContainers.MYSQL;
        String schema = "license_migration_" + UUID.randomUUID().toString().replace("-", "");
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(server + mysql.getDatabaseName()
                + "?useSSL=false&allowPublicKeyRetrieval=true", "root", mysql.getPassword()));
        root.execute("CREATE DATABASE " + schema);
        try {
            DriverManagerDataSource source = new DriverManagerDataSource(server + schema + "?useSSL=false&allowPublicKeyRetrieval=true", "root", mysql.getPassword());
            String location = "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target("3").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO license(order_no,member_id,product_id,license_key,status,issued_at,created_at,updated_at) VALUES('legacy',1,1,'legacy-key','ACTIVE',NOW(),NOW(),NOW())");
            jdbc.update("INSERT INTO processed_event(event_id,consumer_group,event_type,processed_at) VALUES('legacy-event','license','PaymentCompleted',NOW())");
            jdbc.update("INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,topic,partition_key,payload,status,created_at) VALUES('legacy-outbox','License','legacy','LicenseIssued','stove.license.v1','legacy','{}','PENDING',NOW())");
            Flyway flyway = Flyway.configure().dataSource(source).locations(location).load();
            flyway.migrate();
            flyway.validate();
            assertThat(jdbc.queryForObject("SELECT license_key FROM license", String.class)).isEqualTo("legacy-key");
            assertThat(jdbc.queryForObject("SELECT consumer_group FROM processed_event", String.class)).isEqualTo("license");
            assertThat(jdbc.queryForObject("SELECT status FROM outbox_event", String.class)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_license", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM license_purchase", Integer.class)).isZero();
        } finally {
            root.execute("DROP DATABASE " + schema);
        }
    }
}
