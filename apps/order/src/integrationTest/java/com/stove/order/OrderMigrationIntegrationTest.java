package com.stove.order;

import static org.assertj.core.api.Assertions.*;
import com.stove.common.testcontainers.SharedContainers;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.file.Path;
import java.util.UUID;

class OrderMigrationIntegrationTest extends OrderIntegrationTestBase {
    @Test void v10PreservesLegacyOrdersAndQuarantinesOnlyOldPendingEvents() throws Exception {
        var mysql = SharedContainers.MYSQL;
        String schema = "order_migration_" + UUID.randomUUID().toString().replace("-", "");
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        var root = new JdbcTemplate(new DriverManagerDataSource(server + mysql.getDatabaseName()
                + "?useSSL=false&allowPublicKeyRetrieval=true", "root", mysql.getPassword()));
        root.execute("CREATE DATABASE " + schema);
        try {
            var source = new DriverManagerDataSource(server + schema + "?useSSL=false&allowPublicKeyRetrieval=true", "root", mysql.getPassword());
            String location = "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target("9").load().migrate();
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO orders(order_no,member_id,status,total_amount,created_at,updated_at) VALUES('legacy',1,'CREATED',1000,NOW(),NOW())");
            for (String type : new String[]{"OrderCreated", "OrderPlaced"})
                jdbc.update("INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,topic,partition_key,payload,status,created_at) VALUES(?, 'Order', 'legacy', ?, 'stove.order.v1', 'legacy', '{}', 'PENDING', NOW())", type, type);
            try (var connection = source.getConnection()) {
                var script = new FileSystemResource(Path.of("../../scripts/order-transition.sql"));
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
