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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;

class OrderMigrationIntegrationTest extends OrderIntegrationTestBase {
    private static final String LOCATION = "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath();

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
        MySQLContainer<? extends MySQLContainer<?>> mysql = SharedContainers.MYSQL;
        String schema = "order_exclusive_" + UUID.randomUUID().toString().replace("-", "");
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(server + mysql.getDatabaseName()
                + "?useSSL=false&allowPublicKeyRetrieval=true", "root", mysql.getPassword()));
        root.execute("CREATE DATABASE " + schema);
        try {
            DriverManagerDataSource source = new DriverManagerDataSource(server + schema + "?useSSL=false&allowPublicKeyRetrieval=true", "root", mysql.getPassword());
            Flyway.configure().dataSource(source).locations(LOCATION).target("10").load().migrate();
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
