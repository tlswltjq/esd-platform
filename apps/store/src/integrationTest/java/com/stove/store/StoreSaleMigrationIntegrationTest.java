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

class StoreSaleMigrationIntegrationTest {
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
            Flyway.configure().dataSource(source).locations(location).target("1").load().migrate();
            JdbcTemplate jdbc=new JdbcTemplate(source);
            jdbc.update("INSERT INTO store_product(product_id,product_code,title,short_description,thumbnail_url,price,currency,published_at,content,content_version,build_version,updated_at) VALUES(1,'sale-migration','Original','Summary','https://example.com/t.png',1000,'KRW','2026-10-15 00:00:00','Original content',7,'build-7','2026-10-15 00:00:00')");
            Map<String,Object> before=jdbc.queryForMap("SELECT * FROM store_product");
            Flyway.configure().dataSource(source).locations(location).load().migrate();
            assertThat(jdbc.queryForMap("SELECT * FROM store_product")).containsAllEntriesOf(before);
            assertThat(jdbc.queryForObject("SELECT sale_status FROM store_product",String.class)).isEqualTo("ON_SALE");
            assertThat(jdbc.queryForObject("SELECT catalog_version FROM store_product",Long.class)).isEqualTo(7);

        } finally { root.execute("DROP DATABASE "+schema); }
    }
}
