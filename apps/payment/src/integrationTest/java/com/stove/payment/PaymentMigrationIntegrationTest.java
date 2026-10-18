package com.stove.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.testcontainers.SharedContainers;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;

class PaymentMigrationIntegrationTest extends PaymentIntegrationTestBase {
    @Test void v11PreservesLegacyRowsAndQuarantinesOnlyLegacyPendingOrDeadEvents() throws Exception {
        MySQLContainer<? extends MySQLContainer<?>> mysql=SharedContainers.MYSQL;
        String schema="payment_migration_"+UUID.randomUUID().toString().replace("-","");
        String server="jdbc:mysql://"+mysql.getHost()+":"+mysql.getMappedPort(3306)+"/";
        JdbcTemplate root=new JdbcTemplate(new DriverManagerDataSource(server+mysql.getDatabaseName()+"?useSSL=false&allowPublicKeyRetrieval=true","root",mysql.getPassword()));
        root.execute("CREATE DATABASE "+schema);
        try {
            DriverManagerDataSource source=new DriverManagerDataSource(server+schema+"?useSSL=false&allowPublicKeyRetrieval=true","root",mysql.getPassword());
            String location="filesystem:"+Path.of("src/main/resources/db/migration").toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target("10").load().migrate();
            JdbcTemplate jdbc=new JdbcTemplate(source);
            jdbc.update("INSERT INTO payment(order_no,member_id,amount,currency,status,created_at,updated_at) VALUES('legacy',1,1000,'KRW','PAID',NOW(),NOW())");
            jdbc.update("INSERT INTO simulated_pg_transaction(pg_tx_id,order_no,amount,currency,status,created_at) VALUES('legacy-pg','legacy',1000,'KRW','APPROVED',NOW())");
            for(String type:new String[]{"PaymentCompleted","OrderPaymentCompleted","OrderPaymentFailed"})
                jdbc.update("INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,topic,partition_key,payload,status,created_at) VALUES(?,'Payment','legacy',?,'stove.payment.v1','legacy','{}','PENDING',NOW())",type,type);
            jdbc.update("INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,topic,partition_key,payload,status,created_at) VALUES('old-dead','Payment','legacy','PaymentFailed','stove.payment.v1','legacy','{}','DEAD',NOW())");
            try(Connection connection=source.getConnection()) {
                FileSystemResource script=new FileSystemResource(Path.of("../../scripts/payment-transition.sql"));
                ScriptUtils.executeSqlScript(connection,script); ScriptUtils.executeSqlScript(connection,script);
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_legacy_outbox_archive",Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT status FROM payment_legacy_outbox_archive WHERE event_id='PaymentCompleted'",String.class)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id='PaymentCompleted'",String.class)).isEqualTo("DEAD");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE status='PENDING'",Integer.class)).isEqualTo(2);
            Flyway.configure().dataSource(source).locations(location).load().migrate();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM simulated_pg_transaction",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_payment",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM simulated_payment_charge",Integer.class)).isZero();
        } finally { root.execute("DROP DATABASE "+schema); }
    }
}
