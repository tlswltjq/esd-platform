package com.stove.catalog;

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

class CatalogSaleMigrationIntegrationTest {
    @Test void existingPublicRowsKeepTheirValuesAndStartWithAnUnrestrictedMatchingCatalogVersion() {
        MySQLContainer<?> mysql=SharedContainers.MYSQL;
        mysql.start();
        String server="jdbc:mysql://"+mysql.getHost()+":"+mysql.getMappedPort(3306)+"/";
        String options="?useSSL=false&allowPublicKeyRetrieval=true";
        JdbcTemplate root=new JdbcTemplate(new DriverManagerDataSource(server+mysql.getDatabaseName()+options,"root",mysql.getPassword()));
        String schema="sales_migration_"+UUID.randomUUID().toString().replace("-","");
        root.execute("CREATE DATABASE "+schema);
        try {
            DriverManagerDataSource source=new DriverManagerDataSource(server+schema+options,"root",mysql.getPassword());
            String location="filesystem:"+Path.of("src/main/resources/db/migration").toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target("11").load().migrate();
            JdbcTemplate jdbc=new JdbcTemplate(source);
            jdbc.update("INSERT INTO catalog_release(release_request_id,request_event_id,review_request_id,project_id,product_code,content_version,snapshot,status,requested_at,processed_at) VALUES(101,'event-101',91,81,'sale-migration',7,'{}','COMPLETED','2026-10-15 00:00:00','2026-10-15 00:00:00')");
            jdbc.update("INSERT INTO catalog_product(project_id,product_code,owner_subject,current_release_request_id,published_at,updated_at) VALUES(81,'sale-migration','original-owner',101,'2026-10-15 00:00:00','2026-10-15 00:00:00')");
            Map<String,Object> before=jdbc.queryForMap("SELECT * FROM catalog_product");
            Flyway.configure().dataSource(source).locations(location).load().migrate();
            assertThat(jdbc.queryForMap("SELECT * FROM catalog_product")).containsAllEntriesOf(before);
            assertThat(jdbc.queryForObject("SELECT sale_status FROM catalog_product",String.class)).isEqualTo("ON_SALE");
            assertThat(jdbc.queryForObject("SELECT catalog_version FROM catalog_product",Long.class)).isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT platform_restricted FROM catalog_product",Boolean.class)).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_sale_change",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT content_version FROM catalog_release",Long.class)).isEqualTo(7);
        } finally { root.execute("DROP DATABASE "+schema); }
    }
}
