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
            // V10: 이미 지급된 보유권은 등록된 구매 근거와 ACTIVE 보유권 행으로 옮겨진다.
            assertThat(merged.get("status")).isEqualTo("REGISTERED");
            assertThat(jdbc.queryForObject("SELECT status FROM license_registration WHERE license_id=?", String.class,
                    merged.get("license_id"))).isEqualTo("ACTIVE");
            assertThat(merged.get("suspension_request_event_id")).isNull();
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
    void v10SplitsGrantedRightsIntoRegisteredPurchasesAndRegistrationsWithoutLosingSuspensions() {
        MySQLContainer<? extends MySQLContainer<?>> mysql = SharedContainers.MYSQL;
        String schema = "license_storage_" + UUID.randomUUID().toString().replace("-", "");
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(server + mysql.getDatabaseName()
                + "?useSSL=false&allowPublicKeyRetrieval=true", "root", mysql.getPassword()));
        root.execute("CREATE DATABASE " + schema);
        try {
            DriverManagerDataSource source = new DriverManagerDataSource(server + schema + "?useSSL=false&allowPublicKeyRetrieval=true", "root", mysql.getPassword());
            Flyway.configure().dataSource(source).locations(LOCATION).target("9").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(source);
            PaidOrder active = paid("usr_buyer", 1L, "Game-1");
            PaidOrder suspended = paid("usr_buyer", 2L, "Game-2");
            String activeId = UUID.randomUUID().toString();
            String suspendedId = UUID.randomUUID().toString();
            insertV9(jdbc, activeId, active, "ACTIVE", null);
            insertV9(jdbc, suspendedId, suspended, "SUSPENDED", "suspend-1");
            Flyway flyway = Flyway.configure().dataSource(source).locations(LOCATION).load();
            flyway.migrate(); flyway.validate();
            Map<String, Object> registered = jdbc.queryForMap("SELECT status,suspended_from FROM product_license WHERE license_id=?", activeId);
            assertThat(registered.get("status")).isEqualTo("REGISTERED");
            assertThat(registered.get("suspended_from")).isNull();
            Map<String, Object> paused = jdbc.queryForMap("SELECT status,suspended_from,suspension_request_event_id FROM product_license WHERE license_id=?", suspendedId);
            assertThat(paused.get("status")).isEqualTo("SUSPENDED");
            assertThat(paused.get("suspended_from")).isEqualTo("REGISTERED");
            assertThat(paused.get("suspension_request_event_id")).isEqualTo("suspend-1");
            Map<String, Object> first = jdbc.queryForMap("SELECT status,order_no,product_code,ended_at FROM license_registration WHERE license_id=?", activeId);
            assertThat(first.get("status")).isEqualTo("ACTIVE");
            assertThat(first.get("order_no")).isEqualTo(active.orderNo());
            assertThat(first.get("product_code")).isEqualTo("Game-1");
            assertThat(first.get("ended_at")).isNull();
            assertThat(jdbc.queryForObject("SELECT status FROM license_registration WHERE license_id=?", String.class, suspendedId)).isEqualTo("SUSPENDED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM license_registration", Integer.class)).isEqualTo(2);
            // 고객·상품당 1행 제한이 풀리고, ACTIVE 보유권만 DB가 하나로 막는다.
            PaidOrder again = paid("usr_buyer", 1L, "Game-1");
            insertV10(jdbc, UUID.randomUUID().toString(), again);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_license WHERE product_id=1", Integer.class)).isEqualTo(2);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO license_registration(registration_id,license_id,order_no,buyer_subject,product_id,product_code,title,status,registered_at) "
                    + "SELECT UUID(),license_id,order_no,buyer_subject,product_id,product_code,title,'ACTIVE',NOW(6) FROM product_license WHERE order_no=?", again.orderNo()))
                    .hasStackTraceContaining("uk_license_registration_active");
        } finally {
            root.execute("DROP DATABASE " + schema);
        }
    }

    @Test
    void v12KeepsExistingRestorationAndCorrectionRowsAndAllowsRestoredAtBeforeTheOrderClock() {
        MySQLContainer<? extends MySQLContainer<?>> mysql = SharedContainers.MYSQL;
        String schema = "license_skew_" + UUID.randomUUID().toString().replace("-", "");
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(server + mysql.getDatabaseName()
                + "?useSSL=false&allowPublicKeyRetrieval=true", "root", mysql.getPassword()));
        root.execute("CREATE DATABASE " + schema);
        try {
            DriverManagerDataSource source = new DriverManagerDataSource(server + schema + "?useSSL=false&allowPublicKeyRetrieval=true", "root", mysql.getPassword());
            Flyway.configure().dataSource(source).locations(LOCATION).target("11").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(source);
            PaidOrder order = paid();
            String licenseId = UUID.randomUUID().toString();
            insertV10(jdbc, licenseId, order);
            Instant at = Instant.parse("2026-11-01T00:00:00.123456Z");
            for (String suspension : List.of("old-restore", "old-correct", "skew-restore", "skew-correct")) insertSuspension(jdbc, suspension, licenseId, order, at);
            insertRestoration(jdbc, "old-restore", licenseId, at, at.plusSeconds(1));
            insertCorrection(jdbc, "old-correct", licenseId, at, at.plusSeconds(1));
            // V11까지는 처리 시각(License 시계)이 요청 발생 시각(Order 시계)보다 앞서면 CHECK가 막는다.
            assertThatThrownBy(() -> insertRestoration(jdbc, "skew-restore", licenseId, at.plusSeconds(5), at)).hasStackTraceContaining("chk_license_restoration_time");
            assertThatThrownBy(() -> insertCorrection(jdbc, "skew-correct", licenseId, at.plusSeconds(5), at)).hasStackTraceContaining("chk_license_correction_time");
            List<Map<String, Object>> restorations = jdbc.queryForList("SELECT * FROM license_restoration ORDER BY id");
            List<Map<String, Object>> corrections = jdbc.queryForList("SELECT * FROM license_approval_correction ORDER BY request_event_id");
            Flyway flyway = Flyway.configure().dataSource(source).locations(LOCATION).load();
            flyway.migrate(); flyway.validate();
            assertThat(jdbc.queryForList("SELECT * FROM license_restoration ORDER BY id")).isEqualTo(restorations);
            assertThat(jdbc.queryForList("SELECT * FROM license_approval_correction ORDER BY request_event_id")).isEqualTo(corrections);
            insertRestoration(jdbc, "skew-restore", licenseId, at.plusSeconds(5), at);
            insertCorrection(jdbc, "skew-correct", licenseId, at.plusSeconds(5), at);
            assertThat(jdbc.queryForObject("SELECT restored_at FROM license_restoration WHERE request_event_id='req-skew-restore'", Timestamp.class).toInstant()).isEqualTo(at);
            assertThat(jdbc.queryForObject("SELECT request_occurred_at FROM license_approval_correction WHERE request_event_id='req-skew-correct'", Timestamp.class).toInstant()).isEqualTo(at.plusSeconds(5));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema=DATABASE() AND constraint_name IN ('chk_license_restoration_time','chk_license_correction_time')", Integer.class)).isZero();
        } finally {
            root.execute("DROP DATABASE " + schema);
        }
    }

    private void insertSuspension(JdbcTemplate jdbc, String requestEventId, String licenseId, PaidOrder order, Instant at) {
        jdbc.update("INSERT INTO license_suspension(request_event_id,request_occurred_at,refund_id,license_id,order_no,buyer_subject,product_id,product_code,payment_id,suspended_at,completed_event_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                requestEventId, Timestamp.from(at), UUID.randomUUID().toString(), licenseId, order.orderNo(), order.buyerSubject(), order.productId(), order.productCode(),
                order.paymentId(), Timestamp.from(at), "done-" + requestEventId);
    }

    private void insertRestoration(JdbcTemplate jdbc, String suspension, String licenseId, Instant occurredAt, Instant restoredAt) {
        jdbc.update("INSERT INTO license_restoration(request_event_id,request_fingerprint,request_occurred_at,refund_id,attempt_id,failure_event_id,license_id,suspension_request_event_id,completed_event_id,restored_at,returned_to_storage) VALUES(?,?,?,?,?,?,?,?,?,?,FALSE)",
                "req-" + suspension, "fp-" + suspension, Timestamp.from(occurredAt), UUID.randomUUID().toString(), UUID.randomUUID().toString(), "failure-" + suspension,
                licenseId, suspension, "restored-" + suspension, Timestamp.from(restoredAt));
    }

    private void insertCorrection(JdbcTemplate jdbc, String suspension, String licenseId, Instant occurredAt, Instant restoredAt) {
        jdbc.update("INSERT INTO license_approval_correction(request_event_id,request_fingerprint,request_occurred_at,stop_request_id,suspension_request_event_id,license_id,completed_event_id,restored_at,returned_to_storage) VALUES(?,?,?,?,?,?,?,?,FALSE)",
                "req-" + suspension, "fp-" + suspension, Timestamp.from(occurredAt), UUID.randomUUID().toString(), suspension, licenseId, "corrected-" + suspension, Timestamp.from(restoredAt));
    }

    private void insertV9(JdbcTemplate jdbc, String licenseId, PaidOrder order, String status, String suspension) {
        jdbc.update("INSERT INTO product_license(license_id,buyer_subject,product_id,product_code,title,acquired_at,event_id,occurred_at,order_no,request_event_id,"
                        + "payment_result_event_id,payment_id,content_version,amount,currency,received_at,status,suspension_request_event_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                licenseId, order.buyerSubject(), order.productId(), order.productCode(), order.title(), Timestamp.from(Instant.parse("2026-10-17T01:03:00.123456Z")),
                order.eventId(), Timestamp.from(order.occurredAt()), order.orderNo(), order.requestEventId(), order.paymentResultEventId(), order.paymentId(),
                order.contentVersion(), order.amount(), order.currency(), Timestamp.from(Instant.parse("2026-10-17T01:03:00.654321Z")), status, suspension);
    }

    private void insertV10(JdbcTemplate jdbc, String licenseId, PaidOrder order) {
        jdbc.update("INSERT INTO product_license(license_id,buyer_subject,product_id,product_code,title,acquired_at,event_id,occurred_at,order_no,request_event_id,"
                        + "payment_result_event_id,payment_id,content_version,amount,currency,received_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                licenseId, order.buyerSubject(), order.productId(), order.productCode(), order.title(), Timestamp.from(Instant.parse("2026-10-18T01:03:00.123456Z")),
                order.eventId(), Timestamp.from(order.occurredAt()), order.orderNo(), order.requestEventId(), order.paymentResultEventId(), order.paymentId(),
                order.contentVersion(), order.amount(), order.currency(), Timestamp.from(Instant.parse("2026-10-18T01:03:00.654321Z")));
    }

    @Test
    void v6PreservesLegacyRightsInboxAndArchivesPendingEventsBeforeRelayActivation() {
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
            assertThat(jdbc.queryForObject("SELECT status FROM outbox_event", String.class)).isEqualTo("DEAD");
            assertThat(jdbc.queryForObject("SELECT status FROM license_legacy_outbox_archive", String.class)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT event_id FROM license_legacy_outbox_archive", String.class)).isEqualTo("legacy-outbox");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_license", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM license_purchase", Integer.class)).isZero();
        } finally {
            root.execute("DROP DATABASE " + schema);
        }
    }
}
