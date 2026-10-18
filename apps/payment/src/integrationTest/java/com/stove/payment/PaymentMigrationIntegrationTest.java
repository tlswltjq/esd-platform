package com.stove.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.stove.common.testcontainers.SharedContainers;
import com.stove.payment.adapter.pg.SimulatedPgHttpGateway;
import com.stove.payment.core.domain.Payment;
import com.stove.payment.core.domain.PaymentRepository;
import com.stove.payment.core.domain.PaymentStatus;
import com.stove.payment.core.domain.PgResult;
import com.stove.payment.core.service.ReceiveOrderPayment;
import com.stove.payment.core.service.ResolvePayment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.springframework.web.client.RestClient;

class PaymentMigrationIntegrationTest extends PaymentIntegrationTestBase {
    @Autowired ReceiveOrderPayment receive;
    @Autowired ResolvePayment resolve;
    @Autowired PaymentRepository payments;

    @Test void v12KeepsUnknownFeesAndOfflineImportRecoversPendingWithOriginalDecision() throws Exception {
        MySQLContainer<?> mysql=SharedContainers.MYSQL;
        Payment pending=receive.execute(PaymentFixtures.request());
        String schema="pg_import_"+UUID.randomUUID().toString().replace("-","");
        String server="jdbc:mysql://"+mysql.getHost()+":"+mysql.getMappedPort(3306)+"/";
        String options="?useSSL=false&allowPublicKeyRetrieval=true";
        JdbcTemplate root=new JdbcTemplate(new DriverManagerDataSource(server+mysql.getDatabaseName()+options,"root",mysql.getPassword()));
        root.execute("CREATE DATABASE "+schema);
        try {
            DriverManagerDataSource source=new DriverManagerDataSource(server+schema+options,"root",mysql.getPassword());JdbcTemplate jdbc=new JdbcTemplate(source);
            String location="filesystem:"+Path.of("src/main/resources/db/migration").toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target("11").load().migrate();
            String columns="payment_id,order_no,request_event_id,order_occurred_at,buyer_subject,product_id,product_code,title,content_version,list_amount,discount_amount,amount,currency,provider,status,next_attempt_at,created_at,updated_at";
            jdbc.update("INSERT INTO purchase_payment("+columns+") SELECT "+columns+" FROM "+mysql.getDatabaseName()+".purchase_payment WHERE payment_id=?",pending.getPaymentId());
            String transaction=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO simulated_payment_charge VALUES(?,?,?,'KRW',?,'2026-10-01 00:00:00.000001','APPROVED',NULL,NULL)",pending.getPaymentId(),pending.getOrderNo(),pending.getAmount(),transaction);
            Flyway.configure().dataSource(source).locations(location).load().migrate();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_payment WHERE pg_fee_amount IS NULL",Long.class)).isEqualTo(1);
            for(int attempt=0;attempt<3;attempt++) {
                if(attempt==2) ExternalPgProcess.JDBC.update("UPDATE pg_charge SET pg_fee_policy_id='conflicting-fixture',pg_fee_rate_bps=300,pg_fee_amount=300 WHERE payment_id=?",pending.getPaymentId());
                ProcessBuilder importer=new ProcessBuilder("python3",Path.of("../../scripts/migrate-mock-pg.py").toString(),
                        "--source-container",mysql.getContainerId(),"--source-db",schema,"--source-user","root",
                        "--target-container",ExternalPgProcess.MYSQL.getContainerId());
                importer.environment().put("SOURCE_MYSQL_PASSWORD",mysql.getPassword());
                importer.environment().put("TARGET_MYSQL_PASSWORD",ExternalPgProcess.MYSQL.getPassword());
                Process process=importer.redirectErrorStream(true).start();String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
                int exit=process.waitFor();
                if(attempt==2) {
                    assertThat(exit).isNotZero();assertThat(output).contains("Existing target decision conflicts with source");
                    ExternalPgProcess.JDBC.update("UPDATE pg_charge SET pg_fee_policy_id=NULL,pg_fee_rate_bps=NULL,pg_fee_amount=NULL WHERE payment_id=?",pending.getPaymentId());
                } else {
                    assertThat(exit).withFailMessage(output).isZero();assertThat(output).contains("\"verified\": 1");
                }
            }
            SimulatedPgHttpGateway gateway=new SimulatedPgHttpGateway(RestClient.builder().baseUrl(ExternalPgProcess.baseUrl())
                    .defaultHeader("X-Pg-Api-Key",ExternalPgProcess.PAYMENT_KEY).build());
            PgResult original=gateway.charge(pending.chargeRequest());assertThat(original.transactionId()).isEqualTo(transaction);assertThat(original.fee()).isNull();
            resolve.execute(pending.getPaymentId());Payment confirmed=payments.findById(pending.getId()).orElseThrow();
            assertThat(confirmed.getStatus()).isEqualTo(PaymentStatus.PAID);assertThat(confirmed.getProviderTransactionId()).isEqualTo(transaction);
            assertThat(confirmed.getPgFeeAmount()).isNull();
        } finally {
            root.execute("DROP DATABASE "+schema);
            ExternalPgProcess.JDBC.update("DELETE FROM pg_charge WHERE payment_id=?",pending.getPaymentId());
        }
    }
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
