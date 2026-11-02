package com.stove.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.testcontainers.SharedContainers;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

class StoreListingMigrationIntegrationTest {
    @Test void existingBusinessDataIsPreservedAndListingStartsWithTheAutomaticSalesRule() {
        MySQLContainer<?> mysql = SharedContainers.MYSQL;
        mysql.start();
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true";
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(server + mysql.getDatabaseName() + options, "root", mysql.getPassword()));
        String schema = "listing_migration_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE DATABASE " + schema);
        try {
            DriverManagerDataSource source = new DriverManagerDataSource(server + schema + options, "root", mysql.getPassword());
            String location = "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target("2").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(source);

            jdbc.update("INSERT INTO store_product(product_id,product_code,title,short_description,thumbnail_url,price,currency,published_at,content,content_version,build_version,updated_at,sale_status,catalog_version) VALUES(1,'listing-migration','Original','Summary','https://example.com/t.png',1000,'KRW','2026-10-15','Original content',7,'build-7','2026-10-15','ON_SALE',9)");
            jdbc.update("INSERT INTO store_product SELECT 2,'stopped-migration',title,short_description,thumbnail_url,price,currency,published_at,content,video_url,content_version,build_version,updated_at,'STOPPED',catalog_version FROM store_product WHERE product_id=1");
            jdbc.update("INSERT INTO store_product SELECT 3,'blocked-migration',title,short_description,thumbnail_url,price,currency,published_at,content,video_url,content_version,build_version,updated_at,'BLOCKED',catalog_version FROM store_product WHERE product_id=1");
            Map<String, Object> before = jdbc.queryForMap("SELECT * FROM store_product WHERE product_id=1");

            Flyway.configure().dataSource(source).locations(location).load().migrate();

            assertThat(jdbc.queryForMap("SELECT * FROM store_product WHERE product_id=1")).containsAllEntriesOf(before);
            assertThat(jdbc.queryForList("SELECT listed FROM store_product ORDER BY product_id", Boolean.class)).containsExactly(true, false, false);
            assertThatThrownBy(() -> jdbc.update("UPDATE store_product SET listed=2 WHERE product_id=1"))
                    .isInstanceOfSatisfying(UncategorizedSQLException.class, failure -> assertThat(failure.getSQLException().getErrorCode()).isEqualTo(3819));

        } finally { root.execute("DROP DATABASE " + schema); }
    }
}
