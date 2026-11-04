package com.stove.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.testcontainers.SharedContainers;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

class StoreReconciliationMigrationIntegrationTest {
    @Test void addingTheAuditPreservesTheExistingProjectionAndDoesNotFabricateRepairs() {
        MySQLContainer<?> mysql = SharedContainers.MYSQL;
        mysql.start();
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true";
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(server + mysql.getDatabaseName() + options, "root", mysql.getPassword()));
        String schema = "reconciliation_migration_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE DATABASE " + schema);
        try {
            DriverManagerDataSource source = new DriverManagerDataSource(server + schema + options, "root", mysql.getPassword());
            String location = "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target("3").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO store_product(product_id,product_code,title,short_description,thumbnail_url,price,currency,published_at,content,content_version,build_version,updated_at,sale_status,catalog_version,listed) VALUES(1,'reconciliation-migration','Original','Summary','https://example.com/t.png',1000,'KRW','2026-10-15','Original content',7,'build-7','2026-10-15','STOPPED',9,FALSE)");
            Map<String, Object> before = jdbc.queryForMap("SELECT * FROM store_product WHERE product_id=1");
            Flyway.configure().dataSource(source).locations(location).load().migrate();
            assertThat(jdbc.queryForMap("SELECT * FROM store_product WHERE product_id=1")).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM store_reconciliation", Long.class)).isZero();
        } finally { root.execute("DROP DATABASE " + schema); }
    }
}
