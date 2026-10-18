package com.stove.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.testcontainers.SharedContainers;
import java.nio.file.Path;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

class SettlementMigrationIntegrationTest extends SettlementIntegrationTestBase {
    @Test void v5ToV6ToV7PreservesOldLedgerAndInboxAndUsesCaseSensitiveNewKeys() {
        MySQLContainer<?> mysql=SharedContainers.MYSQL;
        String schema="settlement_migration_"+UUID.randomUUID().toString().replace("-","");
        String server="jdbc:mysql://"+mysql.getHost()+":"+mysql.getMappedPort(3306)+"/";
        JdbcTemplate root=new JdbcTemplate(new DriverManagerDataSource(server+mysql.getDatabaseName()+"?useSSL=false&allowPublicKeyRetrieval=true","root",mysql.getPassword()));
        root.execute("CREATE DATABASE "+schema);
        try {
            DriverManagerDataSource source=new DriverManagerDataSource(server+schema+"?useSSL=false&allowPublicKeyRetrieval=true","root",mysql.getPassword());JdbcTemplate jdbc=new JdbcTemplate(source);
            String location="filesystem:"+Path.of("src/main/resources/db/migration").toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target("5").load().migrate();
            jdbc.update("INSERT INTO settlement_record(order_no,product_id,seller_id,sale_type,record_type,gross_amount,fee_rate,fee_amount,net_amount,settlement_month,created_at,updated_at) VALUES('historical',1,1,'PARTNER','SALE',10000,0.3,3000,7000,'2026-09',NOW(),NOW())");
            jdbc.update("INSERT INTO processed_event(event_id,consumer_group,event_type,processed_at) VALUES('CaseSensitive','old-settlement-group','PaymentCompleted',NOW())");
            String checksums=jdbc.queryForList("SELECT checksum FROM flyway_schema_history ORDER BY installed_rank").toString();
            Flyway.configure().dataSource(source).locations(location).target("6").load().migrate();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_sale",Long.class)).isZero();
            Flyway.configure().dataSource(source).locations(location).load().migrate();
            assertThat(jdbc.queryForList("SELECT checksum FROM flyway_schema_history WHERE version IN ('1','2','3','4','5') ORDER BY installed_rank").toString()).isEqualTo(checksums);
            assertThat(jdbc.queryForObject("SELECT net_amount FROM settlement_record WHERE order_no='historical'",Long.class)).isEqualTo(7000);
            jdbc.update("INSERT INTO processed_event(event_id,consumer_group,event_type,processed_at) VALUES('casesensitive','old-settlement-group','PaymentCompleted',NOW())");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event",Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_receipt",Long.class)).isZero();
            Flyway.configure().dataSource(source).locations(location).load().validate();
        } finally { root.execute("DROP DATABASE "+schema); }
    }
}
