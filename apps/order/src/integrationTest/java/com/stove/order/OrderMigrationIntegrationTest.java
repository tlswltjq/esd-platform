package com.stove.order;

import static com.stove.order.OrderFixtures.id;
import static com.stove.order.OrderFixtures.product;
import static com.stove.order.OrderFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.testcontainers.SharedContainers;
import com.stove.order.core.domain.Order;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;

@Tag("migration")
class OrderMigrationIntegrationTest extends OrderIntegrationTestBase {
    private static final String LOCATION = "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath();

    @Test void v23PreservesAttemptsWithoutGuessingPreparationFromTheOrderStartTime() {
        withVersion("22",source->{
            JdbcTemplate jdbc=new JdbcTemplate(source);insert(jdbc,"PAID");String order=jdbc.queryForObject("SELECT order_no FROM purchase_order",String.class);
            String refund=id();String attempt=id();Instant at=Instant.parse("2026-10-29T01:00:00.123456Z");
            jdbc.update("INSERT INTO order_refund(refund_id,order_no,received_at,automatic_refund_eligible,status,current_attempt_id) VALUES(?,?,?,TRUE,'PENDING_PAYMENT_REFUND',?)",refund,order,Timestamp.from(at),attempt);
            jdbc.update("INSERT INTO order_refund_attempt(attempt_id,refund_id,attempt_no,started_at,status) VALUES(?,?,1,?,'PENDING')",attempt,refund,Timestamp.from(at));
            Map<String,Object> before=jdbc.queryForMap("SELECT * FROM order_refund_attempt");Map<String,Object> parent=jdbc.queryForMap("SELECT * FROM order_refund");
            Flyway flyway=Flyway.configure().dataSource(source).locations(LOCATION).load();flyway.migrate();flyway.validate();
            assertThat(jdbc.queryForMap("SELECT * FROM order_refund_attempt")).containsAllEntriesOf(before).containsEntry("preparation_event_id",null).containsEntry("first_refund_prepared_at",null).containsEntry("confirmation_delay_at",null).containsEntry("preparation_fingerprint",null);
            assertThat(jdbc.queryForMap("SELECT * FROM order_refund")).isEqualTo(parent);
            assertThatThrownBy(()->jdbc.update("UPDATE order_refund_attempt SET first_refund_prepared_at=started_at WHERE attempt_id=?",attempt)).isInstanceOf(DataAccessException.class).rootCause().isInstanceOfSatisfying(SQLException.class,failure->assertThat(failure.getErrorCode()).isEqualTo(3819));
        });
    }

    @Test void v22PreservesExistingReviewFactsAndDoesNotInventAnyApprovalStop() {
        withVersion("21",source->{
            JdbcTemplate jdbc=new JdbcTemplate(source); insert(jdbc,"PAID"); String order=jdbc.queryForObject("SELECT order_no FROM purchase_order",String.class);
            String refund=id(); String decision=id(); Instant at=Instant.parse("2026-10-29T01:00:00.123456Z");
            jdbc.update("INSERT INTO order_refund(refund_id,order_no,received_at,automatic_refund_eligible,status,current_review_id,review_request_id,review_decision,review_operator_subject,review_reason,reviewed_at) VALUES(?,?,?,FALSE,'PENDING_LICENSE_GRANT',?,?,'APPROVED','admin','original',?)",refund,order,Timestamp.from(at),refund,decision,Timestamp.from(at));
            jdbc.update("INSERT INTO order_refund_review_round(review_id,refund_id,review_no,buyer_subject,received_at,decision_request_id,decision,operator_subject,decision_reason,decided_at) VALUES(?,?,1,'usr_buyer',?,?,'APPROVED','admin','original',?)",refund,refund,Timestamp.from(at),decision,Timestamp.from(at));
            Map<String,Object> before=jdbc.queryForMap("SELECT * FROM order_refund_review_round"); Map<String,Object> parent=jdbc.queryForMap("SELECT * FROM order_refund");
            Flyway flyway=Flyway.configure().dataSource(source).locations(LOCATION).load(); flyway.migrate(); flyway.validate();
            assertThat(jdbc.queryForMap("SELECT * FROM order_refund_review_round")).containsAllEntriesOf(before).containsEntry("stop_request_id",null).containsEntry("correction_request_id",null);
            assertThat(jdbc.queryForMap("SELECT * FROM order_refund")).isEqualTo(parent);
            assertThatThrownBy(()->jdbc.update("UPDATE order_refund_review_round SET stop_request_id=?",id())).isInstanceOf(DataAccessException.class).rootCause().isInstanceOfSatisfying(SQLException.class,failure->assertThat(failure.getErrorCode()).isEqualTo(3819));
            assertThatThrownBy(()->jdbc.update("INSERT INTO order_refund_review_round(review_id,refund_id,review_no,previous_review_id,buyer_subject,received_at) VALUES(?,?,2,?,'usr_buyer',?)",id(),refund,refund,Timestamp.from(at))).isInstanceOf(DataAccessException.class).rootCause().isInstanceOfSatisfying(SQLException.class,failure->assertThat(failure.getErrorCode()).isEqualTo(3819));
        });
    }

    @Test void v21CopiesOnlyKnownFirstReviewFactsAndKeepsOriginalAuditAndPurchaseRows() {
        withVersion("20", source -> {
            JdbcTemplate jdbc = new JdbcTemplate(source); Instant received = Instant.parse("2026-10-28T12:00:00.123456Z");
            int number = 100;
            for (String state : List.of("PENDING_REVIEW", "APPROVED", "REJECTED", "AUTOMATIC", "LEGACY_WITHOUT_AUDIT")) {
                insert(jdbc, "PAID");
                String orderNo = jdbc.queryForObject("SELECT order_no FROM purchase_order WHERE product_id=1", String.class);
                jdbc.update("UPDATE purchase_order SET product_id=?,product_code=? WHERE order_no=?", number, "migration-product-" + number, orderNo); number++;
                boolean automatic = state.equals("AUTOMATIC"); boolean decided = state.equals("APPROVED") || state.equals("REJECTED");
                String status = state.equals("APPROVED") || automatic || state.equals("LEGACY_WITHOUT_AUDIT") ? "PENDING_LICENSE_GRANT" : state;
                jdbc.update("INSERT INTO order_refund(refund_id,order_no,received_at,automatic_refund_eligible,status,review_request_id,review_decision,review_operator_subject,review_reason,reviewed_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                        id(), orderNo, Timestamp.from(received), automatic, status, decided ? id() : null, decided ? state : null,
                        decided ? "migration-admin" : null, decided ? "기존 실제 판정 사유" : null, decided ? Timestamp.from(received.plusSeconds(1)) : null);
            }
            List<Map<String, Object>> original = jdbc.queryForList("SELECT * FROM order_refund ORDER BY id");
            List<Map<String, Object>> purchases = jdbc.queryForList("SELECT * FROM purchase_order ORDER BY id");
            Flyway flyway = Flyway.configure().dataSource(source).locations(LOCATION).load(); flyway.migrate(); flyway.validate();
            List<Map<String, Object>> migrated = jdbc.queryForList("SELECT * FROM order_refund ORDER BY id");
            for (int index = 0; index < original.size(); index++) assertThat(migrated.get(index)).containsAllEntriesOf(original.get(index));
            assertThat(jdbc.queryForList("SELECT * FROM purchase_order ORDER BY id")).isEqualTo(purchases);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_refund_review_round", Integer.class)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_refund_review_round WHERE decision_request_id IS NOT NULL AND operator_subject='migration-admin'", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_refund_review_round WHERE decision_request_id IS NULL AND operator_subject IS NULL AND customer_reason IS NULL", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_refund WHERE automatic_refund_eligible=TRUE AND current_review_id IS NULL", Integer.class)).isEqualTo(1);
        });
    }

    @Test void v20PreservesV19PendingAndCompletedRefundsWithoutInventingOperatorHistory() {
        withVersion("19", source -> {
            JdbcTemplate jdbc = new JdbcTemplate(source); insert(jdbc, "PAID");
            String orderNo = jdbc.queryForObject("SELECT order_no FROM purchase_order", String.class);
            jdbc.update("INSERT INTO order_refund(refund_id,order_no,received_at,automatic_refund_eligible,status) VALUES(?,?,NOW(6),FALSE,'PENDING_REVIEW')", id(), orderNo);
            jdbc.update("UPDATE purchase_order SET product_id=2,product_code='previous-product'"); insert(jdbc, "PAID");
            String completedOrder = jdbc.queryForObject("SELECT order_no FROM purchase_order WHERE product_id=1", String.class);
            jdbc.update("INSERT INTO order_refund(refund_id,order_no,received_at,automatic_refund_eligible,status,payment_confirmation_event_id,completed_at,payment_confirmation_fingerprint,payment_confirmation_source) VALUES(?,?,NOW(6),TRUE,'COMPLETED',?,NOW(6),?,'PG_RESPONSE')", id(), completedOrder, id(), "a".repeat(64));
            List<Map<String, Object>> beforeOrders = jdbc.queryForList("SELECT * FROM purchase_order ORDER BY id");
            List<Map<String, Object>> beforeRefunds = jdbc.queryForList("SELECT * FROM order_refund ORDER BY id");
            Flyway flyway = Flyway.configure().dataSource(source).locations(LOCATION).load(); flyway.migrate(); flyway.validate();
            assertThat(jdbc.queryForList("SELECT * FROM purchase_order ORDER BY id")).isEqualTo(beforeOrders);
            List<Map<String, Object>> after = jdbc.queryForList("SELECT * FROM order_refund ORDER BY id");
            for (int index = 0; index < beforeRefunds.size(); index++) {
                assertThat(after.get(index)).containsAllEntriesOf(beforeRefunds.get(index));
                assertThat(after.get(index).get("review_request_id")).isNull(); assertThat(after.get(index).get("reviewed_at")).isNull();
            }
        });
    }

    @Test void v11PreservesFailedHistoryAndProtectsThePendingAndPaidSlot() {
        withV10(source -> {
            JdbcTemplate jdbc = new JdbcTemplate(source);
            insert(jdbc, "FAILED"); insert(jdbc, "FAILED"); insert(jdbc, "PENDING");
            List<Map<String, Object>> original = jdbc.queryForList("SELECT * FROM purchase_order ORDER BY id");
            Flyway flyway = Flyway.configure().dataSource(source).locations(LOCATION).load();
            flyway.migrate(); flyway.validate();
            List<Map<String, Object>> migrated = jdbc.queryForList("SELECT * FROM purchase_order ORDER BY id");
            for (int i = 0; i < original.size(); i++) assertThat(migrated.get(i)).containsAllEntriesOf(original.get(i));
            assertThatThrownBy(() -> insert(jdbc, "PAID")).isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update("UPDATE purchase_order SET status='FAILED' WHERE status='PENDING'");
            insert(jdbc, "PAID");
            assertThatThrownBy(() -> insert(jdbc, "PENDING")).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order WHERE status='FAILED'", Integer.class)).isEqualTo(3);
            jdbc.update("UPDATE purchase_order SET product_id=2 WHERE status='PAID'");
            assertThatThrownBy(() -> insert(jdbc, "PENDING")).hasStackTraceContaining("uk_purchase_order_buyer_code");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PAID"})
    void v11RefusesExistingDuplicatePurchasesWithoutRewritingThem(String status) {
        withV10(source -> {
            JdbcTemplate jdbc = new JdbcTemplate(source);
            insert(jdbc, "PENDING"); insert(jdbc, status);
            List<Map<String, Object>> original = jdbc.queryForList("SELECT * FROM purchase_order ORDER BY id");
            assertThatThrownBy(() -> Flyway.configure().dataSource(source).locations(LOCATION).load().migrate())
                    .hasStackTraceContaining("uk_purchase_order_buyer_product");
            assertThat(jdbc.queryForList("SELECT * FROM purchase_order ORDER BY id")).isEqualTo(original);
        });
    }

    private void insert(JdbcTemplate jdbc, String status) {
        Order order = Order.create("usr_buyer", request(), product(), id());
        jdbc.update("INSERT INTO purchase_order(order_no,buyer_subject,request_id,product_id,product_code,title,content_version,list_amount,discount_amount,amount,currency,status,request_event_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(6),NOW(6))",
                order.getOrderNo(), order.getBuyerSubject(), order.getRequestId(), order.getProductId(), order.getProductCode(), order.getTitle(),
                order.getContentVersion(), order.getListAmount(), order.getDiscountAmount(), order.getAmount(), order.getCurrency(), status, order.getRequestEventId());
    }

    private void withV10(Consumer<DriverManagerDataSource> check) {
        withVersion("10", check);
    }

    private void withVersion(String version, Consumer<DriverManagerDataSource> check) {
        MySQLContainer<? extends MySQLContainer<?>> mysql = SharedContainers.MYSQL;
        String schema = "order_exclusive_" + UUID.randomUUID().toString().replace("-", "");
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(server + mysql.getDatabaseName()
                + "?useSSL=false&allowPublicKeyRetrieval=true", "root", mysql.getPassword()));
        root.execute("CREATE DATABASE " + schema);
        try {
            DriverManagerDataSource source = new DriverManagerDataSource(server + schema + "?useSSL=false&allowPublicKeyRetrieval=true", "root", mysql.getPassword());
            Flyway.configure().dataSource(source).locations(LOCATION).target(version).load().migrate();
            check.accept(source);
        } finally { root.execute("DROP DATABASE " + schema); }
    }

    @Test void v10PreservesLegacyOrdersAndQuarantinesOnlyOldPendingEvents() throws Exception {
        MySQLContainer<? extends MySQLContainer<?>> mysql = SharedContainers.MYSQL;
        String schema = "order_migration_" + UUID.randomUUID().toString().replace("-", "");
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(server + mysql.getDatabaseName()
                + "?useSSL=false&allowPublicKeyRetrieval=true", "root", mysql.getPassword()));
        root.execute("CREATE DATABASE " + schema);
        try {
            DriverManagerDataSource source = new DriverManagerDataSource(server + schema + "?useSSL=false&allowPublicKeyRetrieval=true", "root", mysql.getPassword());
            String location = "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target("9").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO orders(order_no,member_id,status,total_amount,created_at,updated_at) VALUES('legacy',1,'CREATED',1000,NOW(),NOW())");
            for (String type : new String[]{"OrderCreated", "OrderPlaced"})
                jdbc.update("INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,topic,partition_key,payload,status,created_at) VALUES(?, 'Order', 'legacy', ?, 'stove.order.v1', 'legacy', '{}', 'PENDING', NOW())", type, type);
            try (Connection connection = source.getConnection()) {
                FileSystemResource script = new FileSystemResource(Path.of("../../tools/migrations/order/order-transition.sql"));
                ScriptUtils.executeSqlScript(connection, script); ScriptUtils.executeSqlScript(connection, script);
            }
            assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id='OrderCreated'", String.class)).isEqualTo("DEAD");
            assertThat(jdbc.queryForObject("SELECT status FROM order_legacy_outbox_archive WHERE event_id='OrderCreated'", String.class)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id='OrderPlaced'", String.class)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_legacy_outbox_archive", Integer.class)).isEqualTo(1);
            Flyway.configure().dataSource(source).locations(location).load().migrate();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order", Integer.class)).isZero();
            assertThat(jdbc.queryForList("SHOW TABLES", String.class)).contains("order_item", "shedlock", "processed_event", "outbox_event");
        } finally { root.execute("DROP DATABASE " + schema); }
    }
}
