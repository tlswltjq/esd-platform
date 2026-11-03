package com.stove.catalog.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.testcontainers.SharedContainers;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

class CatalogWithdrawalMigrationIntegrationTest {
    @Test
    void preservesThePublicReleaseAndIndependentSalesAndListingControls() {
        withOldSchema((jdbc, migration) -> {
            jdbc.update("INSERT INTO catalog_release(release_request_id,request_event_id,review_request_id,project_id,product_code,content_version,snapshot,status,requested_at,processed_at) VALUES(101,'withdrawal-migration',91,81,'MIGRATION',7,'{}','COMPLETED','2026-11-02','2026-11-02')");
            jdbc.update("INSERT INTO catalog_product(project_id,product_code,owner_subject,current_release_request_id,published_at,updated_at,sale_status,catalog_version,platform_restricted,download_restricted,download_restriction_version,download_storage_status,download_storage_next_attempt_at,listing_preference,platform_listing_hidden) VALUES(81,'MIGRATION','owner',101,'2026-11-02','2026-11-02','STOPPED',9,TRUE,TRUE,2,'PENDING','2026-11-02','UNLISTED',TRUE)");
            Map<String, Object> product = jdbc.queryForMap("SELECT * FROM catalog_product");
            Map<String, Object> release = jdbc.queryForMap("SELECT * FROM catalog_release");
            migration.migrate();
            assertThat(jdbc.queryForMap("SELECT * FROM catalog_product")).isEqualTo(product);
            assertThat(jdbc.queryForMap("SELECT * FROM catalog_release")).containsAllEntriesOf(release);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_publication_job", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_release_withdrawal", Integer.class)).isZero();
        });
    }

    private void withOldSchema(BiConsumer<JdbcTemplate, Flyway> check) {
        MySQLContainer<?> mysql = SharedContainers.MYSQL;
        mysql.start();
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true";
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(
                server + mysql.getDatabaseName() + options, "root", mysql.getPassword()));
        String schema = "catalog_withdrawal_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE DATABASE " + schema);
        try {
            DriverManagerDataSource source = new DriverManagerDataSource(server + schema + options, "root", mysql.getPassword());
            String location = "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target("14").load().migrate();
            check.accept(new JdbcTemplate(source), Flyway.configure().dataSource(source).locations(location).load());
        } finally {
            root.execute("DROP DATABASE " + schema);
        }
    }
}
