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
import java.util.HashMap;
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

    /**
     * 결정 125: V32는 지급 확인을 기다리는 옛 환불에 기록된 사실로만 진입 시각을 채운다.
     * 자동 환불은 접수 시각, 심사 환불은 현재 회차 승인 시각, 판정 시각이 없는 옛 행은 접수 시각(실제 진입보다 늦지 않음)이다.
     * 다른 상태는 NULL로 두고, 다른 열은 바꾸지 않는다. 이행 뒤에는 대기 상태가 시각 없이 저장되지 않는다.
     */
    @Test void v32BackfillsTheWaitStartOnlyFromRecordedFactsAndKeepsOtherRowsUnchanged() {
        withVersion("31", source -> {
            JdbcTemplate jdbc = new JdbcTemplate(source);
            Instant received = Instant.parse("2026-11-08T10:00:00.123456Z"); Instant decided = received.plusSeconds(600);
            Instant reconsidered = received.plusSeconds(3600);
            String automatic = refund(jdbc, received, true, "PENDING_LICENSE_GRANT", null);
            String approved = refund(jdbc, received, false, "PENDING_LICENSE_GRANT", decided);
            round(jdbc, approved, approved, 1, null, received, decided);
            String reconsideredRefund = refund(jdbc, received, false, "REJECTED", decided, "REJECTED");
            round(jdbc, reconsideredRefund, reconsideredRefund, 1, null, received, decided, "REJECTED");
            String second = id(); round(jdbc, second, reconsideredRefund, 2, reconsideredRefund, received.plusSeconds(1800), reconsidered, "APPROVED");
            jdbc.update("UPDATE order_refund SET current_review_id=?,status='PENDING_LICENSE_GRANT' WHERE refund_id=?", second, reconsideredRefund);
            String legacy = refund(jdbc, received, false, "PENDING_LICENSE_GRANT", null);
            round(jdbc, legacy, legacy, 1, null, received, null);
            String suspended = refund(jdbc, received, true, "PENDING_LICENSE_SUSPENSION", null);
            jdbc.update("UPDATE order_refund SET suspension_request_event_id=? WHERE refund_id=?", id(), suspended);
            String review = refund(jdbc, received, false, "PENDING_REVIEW", null);
            round(jdbc, review, review, 1, null, received, null);
            List<Map<String, Object>> before = jdbc.queryForList("SELECT * FROM order_refund ORDER BY id");

            Flyway flyway = Flyway.configure().dataSource(source).locations(LOCATION).load(); flyway.migrate(); flyway.validate();

            List<Map<String, Object>> after = jdbc.queryForList("SELECT * FROM order_refund ORDER BY id");
            assertThat(after).hasSize(before.size());
            for (int index = 0; index < before.size(); index++) {
                Map<String, Object> unchanged = new HashMap<>(after.get(index)); unchanged.remove("license_grant_wait_started_at");
                assertThat(unchanged).isEqualTo(before.get(index));
            }
            assertThat(started(jdbc, automatic)).isEqualTo(received);
            assertThat(started(jdbc, approved)).isEqualTo(decided);
            assertThat(started(jdbc, reconsideredRefund)).isEqualTo(reconsidered);
            assertThat(started(jdbc, legacy)).isEqualTo(received);
            assertThat(started(jdbc, suspended)).isNull();
            assertThat(started(jdbc, review)).isNull();
            assertThatThrownBy(() -> jdbc.update("UPDATE order_refund SET license_grant_wait_started_at=NULL WHERE refund_id=?", automatic))
                    .isInstanceOf(DataAccessException.class).rootCause()
                    .isInstanceOfSatisfying(SQLException.class, failure -> assertThat(failure.getErrorCode()).isEqualTo(3819));
            assertThat(jdbc.queryForList("SELECT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='order_refund'", String.class))
                    .contains("ix_order_refund_license_grant_wait");
        });
    }

    private String refund(JdbcTemplate jdbc, Instant received, boolean automatic, String status, Instant reviewedAt) {
        return refund(jdbc, received, automatic, status, reviewedAt, "APPROVED");
    }

    private String refund(JdbcTemplate jdbc, Instant received, boolean automatic, String status, Instant reviewedAt, String decision) {
        insert(jdbc, "PAID");
        String orderNo = jdbc.queryForObject("SELECT order_no FROM purchase_order ORDER BY id DESC LIMIT 1", String.class);
        String refundId = id();
        jdbc.update("INSERT INTO order_refund(refund_id,order_no,received_at,automatic_refund_eligible,status,current_review_id,review_request_id,review_decision,review_operator_subject,review_reason,reviewed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                refundId, orderNo, Timestamp.from(received), automatic, status, automatic ? null : refundId, reviewedAt == null ? null : id(),
                reviewedAt == null ? null : decision, reviewedAt == null ? null : "migration-admin", reviewedAt == null ? null : "기존 판정 사유",
                reviewedAt == null ? null : Timestamp.from(reviewedAt));
        return refundId;
    }

    private void round(JdbcTemplate jdbc, String reviewId, String refundId, int number, String previous, Instant received, Instant decided) {
        round(jdbc, reviewId, refundId, number, previous, received, decided, "APPROVED");
    }

    private void round(JdbcTemplate jdbc, String reviewId, String refundId, int number, String previous, Instant received, Instant decided, String decision) {
        jdbc.update("INSERT INTO order_refund_review_round(review_id,refund_id,review_no,application_request_id,previous_review_id,buyer_subject,customer_reason,received_at,decision_request_id,decision,operator_subject,decision_reason,decided_at) VALUES(?,?,?,?,?,'usr_buyer',?,?,?,?,?,?,?)",
                reviewId, refundId, number, number == 1 ? null : id(), previous, number == 1 ? null : "재심사 사유", Timestamp.from(received),
                decided == null ? null : id(), decided == null ? null : decision, decided == null ? null : "migration-admin",
                decided == null ? null : "승인 사유", decided == null ? null : Timestamp.from(decided));
    }

    private Instant started(JdbcTemplate jdbc, String refundId) {
        Timestamp value = jdbc.queryForObject("SELECT license_grant_wait_started_at FROM order_refund WHERE refund_id=?", Timestamp.class, refundId);
        return value == null ? null : value.toInstant();
    }

    /** 결정 89·98: V28은 구매 슬롯만 없애고 주문을 바꾸지 않는다. V29는 기존 환불에 보관함 반환 false를 더한다. */
    @Test void v28RemovesOnlyThePurchaseSlotAndV29AddsAFalseStorageReturnToExistingRefunds() {
        withVersion("27", source -> {
            JdbcTemplate jdbc = new JdbcTemplate(source); insert(jdbc, "FAILED"); insert(jdbc, "PAID");
            String orderNo = jdbc.queryForObject("SELECT order_no FROM purchase_order WHERE status='PAID'", String.class);
            jdbc.update("INSERT INTO order_refund(refund_id,order_no,received_at,automatic_refund_eligible,status,payment_confirmation_event_id,completed_at,payment_confirmation_fingerprint,payment_confirmation_source)"
                    + " VALUES(?,?,NOW(6),TRUE,'COMPLETED',?,NOW(6),?,'PG_QUERY_UNATTRIBUTED')", id(), orderNo, id(), "a".repeat(64));
            assertThatThrownBy(() -> insert(jdbc, "PENDING")).isInstanceOf(DataIntegrityViolationException.class);
            List<Map<String, Object>> orders = jdbc.queryForList("SELECT * FROM purchase_order ORDER BY id");
            Map<String, Object> refund = jdbc.queryForMap("SELECT * FROM order_refund");
            Flyway flyway = Flyway.configure().dataSource(source).locations(LOCATION).load(); flyway.migrate(); flyway.validate();
            List<Map<String, Object>> migrated = jdbc.queryForList("SELECT * FROM purchase_order ORDER BY id");
            assertThat(migrated).hasSize(orders.size());
            for (int index = 0; index < orders.size(); index++) {
                Map<String, Object> expected = new HashMap<>(orders.get(index)); expected.remove("purchase_guard");
                assertThat(migrated.get(index)).doesNotContainKey("purchase_guard").isEqualTo(expected);
            }
            Map<String, Object> migratedRefund = new HashMap<>(jdbc.queryForMap("SELECT * FROM order_refund"));
            assertThat(migratedRefund.remove("purchase_returned_to_storage")).isIn(false, 0, (byte) 0, Boolean.FALSE);
            assertThat(migratedRefund.remove("license_grant_wait_started_at")).isNull();
            assertThat(migratedRefund).isEqualTo(refund);
            insert(jdbc, "PENDING"); insert(jdbc, "PAID");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order WHERE product_id=1", Integer.class)).isEqualTo(4);
            assertThat(jdbc.queryForList("SELECT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='purchase_order'", String.class))
                    .doesNotContain("uk_purchase_order_buyer_product", "uk_purchase_order_buyer_code");
        });
    }

    @Test void v27KeepsOldCompletionAndPurchaseFactsWithoutInventingAnyAmountDetails() {
        withVersion("26", source -> {
            JdbcTemplate jdbc = new JdbcTemplate(source); insert(jdbc, "PAID");
            String orderNo = jdbc.queryForObject("SELECT order_no FROM purchase_order", String.class);
            jdbc.update("INSERT INTO order_refund(refund_id,order_no,received_at,automatic_refund_eligible,status,payment_confirmation_event_id,completed_at,payment_confirmation_fingerprint,payment_confirmation_source)"
                    + " VALUES(?,?,NOW(6),TRUE,'COMPLETED',?,NOW(6),?,'PG_QUERY_UNATTRIBUTED')", id(), orderNo, id(), "a".repeat(64));
            Map<String, Object> purchase = jdbc.queryForMap("SELECT * FROM purchase_order");
            Map<String, Object> completed = jdbc.queryForMap("SELECT * FROM order_refund");
            Flyway.configure().dataSource(source).locations(LOCATION).load().migrate();
            assertAmountColumnsAreOnlyUnknownAdditions(jdbc.queryForMap("SELECT * FROM purchase_order"), purchase);
            assertAmountColumnsAreOnlyUnknownAdditions(jdbc.queryForMap("SELECT * FROM order_refund"), completed);
            assertThatThrownBy(() -> jdbc.update("UPDATE purchase_order SET pg_paid_amount=amount WHERE order_no=?", orderNo))
                    .hasStackTraceContaining("ck_order_provider_amounts");
            assertThatThrownBy(() -> jdbc.update("UPDATE order_refund SET confirmed_cancellation_amount=10000"))
                    .hasStackTraceContaining("ck_refund_amount_details");
        });
    }

    @Test void v23PreservesAttemptsWithoutGuessingPreparationFromTheOrderStartTime() {
        withVersion("22",source->{
            JdbcTemplate jdbc=new JdbcTemplate(source);insert(jdbc,"PAID");String order=jdbc.queryForObject("SELECT order_no FROM purchase_order",String.class);
            String refund=id();String attempt=id();Instant at=Instant.parse("2026-10-29T01:00:00.123456Z");
            jdbc.update("INSERT INTO order_refund(refund_id,order_no,received_at,automatic_refund_eligible,status,current_attempt_id) VALUES(?,?,?,TRUE,'PENDING_PAYMENT_REFUND',?)",refund,order,Timestamp.from(at),attempt);
            jdbc.update("INSERT INTO order_refund_attempt(attempt_id,refund_id,attempt_no,started_at,status) VALUES(?,?,1,?,'PENDING')",attempt,refund,Timestamp.from(at));
            Map<String,Object> before=jdbc.queryForMap("SELECT * FROM order_refund_attempt");Map<String,Object> parent=jdbc.queryForMap("SELECT * FROM order_refund");
            Flyway flyway=Flyway.configure().dataSource(source).locations(LOCATION).load();flyway.migrate();flyway.validate();
            assertThat(jdbc.queryForMap("SELECT * FROM order_refund_attempt")).containsAllEntriesOf(before).containsEntry("preparation_event_id",null).containsEntry("first_refund_prepared_at",null).containsEntry("confirmation_delay_at",null).containsEntry("preparation_fingerprint",null);
            assertAmountColumnsAreOnlyUnknownAdditions(jdbc.queryForMap("SELECT * FROM order_refund"), parent);
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
            assertAmountColumnsAreOnlyUnknownAdditions(jdbc.queryForMap("SELECT * FROM order_refund"), parent);
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
            assertAmountColumnsAreOnlyUnknownAdditions(jdbc.queryForList("SELECT * FROM purchase_order ORDER BY id"), purchases);
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
            assertAmountColumnsAreOnlyUnknownAdditions(jdbc.queryForList("SELECT * FROM purchase_order ORDER BY id"), beforeOrders);
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
            // V11~V27의 과거 구매 슬롯 계약. V28(결정 89)이 이 슬롯을 없앤다.
            Flyway flyway = Flyway.configure().dataSource(source).locations(LOCATION).target("27").load();
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

    private void assertAmountColumnsAreOnlyUnknownAdditions(List<Map<String, Object>> actual, List<Map<String, Object>> original) {
        assertThat(actual).hasSize(original.size());
        for (int index = 0; index < original.size(); index++) assertAmountColumnsAreOnlyUnknownAdditions(actual.get(index), original.get(index));
    }

    private void assertAmountColumnsAreOnlyUnknownAdditions(Map<String, Object> actual, Map<String, Object> original) {
        Map<String, Object> previousColumns = new HashMap<>(actual);
        for (String column : List.of("pg_paid_amount", "pg_discount_amount", "refund_pg_paid_amount", "refund_pg_discount_amount",
                "confirmed_cancellation_amount", "confirmed_point_refund_amount", "refund_amounts_reported_at", "refund_provider_cancelled_at",
                "refund_amounts_source", "refund_amounts_cancellation_ids_hash")) {
            if (!original.containsKey(column) && previousColumns.containsKey(column)) assertThat(previousColumns.remove(column)).isNull();
        }
        // V29(결정 98): 기존 환불에는 보관함 반환 표시가 false로만 더해진다.
        if (!original.containsKey("purchase_returned_to_storage") && previousColumns.containsKey("purchase_returned_to_storage"))
            assertThat(previousColumns.remove("purchase_returned_to_storage")).isIn(false, 0, (byte) 0);
        // V32(결정 125): 지급 확인 대기 중인 환불만 진입 시각이 채워지고, 다른 상태는 NULL로 더해진다. 값은 v32 테스트가 따로 본다.
        if (!original.containsKey("license_grant_wait_started_at") && previousColumns.containsKey("license_grant_wait_started_at")) {
            Object started = previousColumns.remove("license_grant_wait_started_at");
            if ("PENDING_LICENSE_GRANT".equals(actual.get("status"))) assertThat(started).isNotNull(); else assertThat(started).isNull();
        }
        // V28(결정 89): 구매 슬롯 계산 열만 사라지고 나머지 주문 값은 그대로다.
        Map<String, Object> expected = new HashMap<>(original);
        if (!actual.containsKey("purchase_guard")) expected.remove("purchase_guard");
        assertThat(previousColumns).isEqualTo(expected);
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
