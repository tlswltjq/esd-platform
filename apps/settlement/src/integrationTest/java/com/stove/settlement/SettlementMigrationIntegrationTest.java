package com.stove.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.testcontainers.SharedContainers;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

class SettlementMigrationIntegrationTest extends SettlementIntegrationTestBase {
    @Test void v8ThenV9PreserveClosedAllocationsReportsAndNeverInventFunding() {
        MySQLContainer<?> mysql = SharedContainers.MYSQL;
        String schema = "settlement_v8_" + UUID.randomUUID().toString().replace("-", "");
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(
                server + mysql.getDatabaseName() + "?useSSL=false&allowPublicKeyRetrieval=true", "root", mysql.getPassword()));
        root.execute("CREATE DATABASE " + schema);
        try {
            DriverManagerDataSource source = new DriverManagerDataSource(
                    server + schema + "?useSSL=false&allowPublicKeyRetrieval=true", "root", mysql.getPassword());
            JdbcTemplate jdbc = new JdbcTemplate(source);
            String location = "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target("7").load().migrate();
            for (int index = 1; index <= 2; index++) {
                long amount = index == 1 ? 10000 : 1000;
                long fee = index == 1 ? 300 : 200;
                jdbc.update("""
                        INSERT INTO settlement_sale(event_id,occurred_at,order_no,request_event_id,payment_result_event_id,
                          payment_id,buyer_subject,product_id,product_code,title,content_version,amount,currency,
                          provider,provider_transaction_id,provider_occurred_at,pg_fee_policy_id,pg_fee_rate_bps,pg_fee_amount,
                          seller_subject,platform_policy_id,platform_fee_rate_bps,platform_fee_amount,seller_payout_amount,
                          platform_net_amount,sale_month,settlement_month,received_at)
                        VALUES(?,'2026-09-01 01:02:03.123456',?,?,?,?,'buyer',1,'game-1','Title',1,?,'KRW',
                          'SIMULATED',?,'2026-09-01 01:02:00.123456','OLD_PG_POLICY',300,?,
                          'seller','OLD_PLATFORM_POLICY',1000,?,?,?,'2026-09','2026-09','2026-09-02 01:02:03.654321')
                        """, "event-" + index, UUID.randomUUID().toString(), "request-" + index, "result-" + index,
                        "payment-" + index, amount, "transaction-" + index, fee, amount / 10, amount * 9 / 10, amount / 10 - fee);
            }
            jdbc.update("""
                    INSERT INTO seller_monthly_settlement(seller_subject,settlement_month,status,paid_amount,
                      platform_fee_amount,seller_payout_amount,pg_fee_amount,platform_net_amount,record_count,created_at,updated_at,closed_at)
                    VALUES('seller','2026-09','CLOSED',11000,1100,9900,500,600,2,
                      '2026-09-02 01:02:03.654321','2026-10-02 03:04:05.123456','2026-10-02 03:04:05.123456')
                    """);
            jdbc.update("""
                    INSERT INTO pg_receipt(provider,provider_settlement_id,currency,cutoff_at,paid_at,provider_status,
                      payment_amount_total,pg_fee_amount_total,payout_amount,record_count,status,reported_by,reported_at,last_checked_at)
                    VALUES('SIMULATED','report-1','KRW','2026-09-30','2026-10-01','SIMULATED_PAID',
                      10000,350,9650,1,'MISMATCH','admin','2026-10-02','2026-10-02')
                    """);
            jdbc.update("""
                    INSERT INTO pg_receipt_item(receipt_id,item_position,payment_id,order_no,amount,currency,provider,
                      provider_transaction_id,provider_occurred_at,pg_fee_policy_id,pg_fee_rate_bps,pg_fee_amount,mismatch_code,mismatch_reason)
                    SELECT 1,0,payment_id,order_no,amount,currency,provider,provider_transaction_id,provider_occurred_at,
                      pg_fee_policy_id,pg_fee_rate_bps,350,'DIFFERENT_TRANSACTION','Historical mismatch' FROM settlement_sale WHERE id=1
                    """);
            List<Map<String, Object>> checksums = jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank");
            List<Map<String, Object>> salesBefore = jdbc.queryForList("SELECT * FROM settlement_sale ORDER BY id");
            Map<String, Object> monthBefore = jdbc.queryForMap("SELECT * FROM seller_monthly_settlement");
            Map<String, Object> receiptBefore = jdbc.queryForMap("SELECT * FROM pg_receipt");
            Map<String, Object> itemBefore = jdbc.queryForMap("SELECT * FROM pg_receipt_item");
            Flyway.configure().dataSource(source).locations(location).target("8").load().migrate();
            assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version<>'8' ORDER BY installed_rank")).isEqualTo(checksums);
            // 이름만 바뀐 과거 비용 열을 복원해 원매출 전체와 대조한다. 음수 플랫폼 잔여액도 보존한다.
            for (int index = 1; index <= 2; index++) {
                Map<String, Object> after = jdbc.queryForMap("SELECT * FROM settlement_sale WHERE id=?", index);
                for (String column : List.of("provider_occurred_at", "pg_fee_policy_id", "pg_fee_rate_bps", "pg_fee_amount", "platform_net_amount"))
                    after.put(column, after.remove("legacy_" + column));
                assertThat(after).isEqualTo(salesBefore.get(index - 1));
            }
            Map<String, Object> monthAfter = jdbc.queryForMap("SELECT * FROM seller_monthly_settlement");
            for (String column : List.of("pg_fee_amount", "platform_net_amount")) restoreLegacyColumn(monthAfter, column);
            assertThat(monthAfter).isEqualTo(monthBefore);
            assertThat(jdbc.queryForMap("SELECT * FROM pg_receipt")).isEqualTo(receiptBefore);
            assertThat(jdbc.queryForMap("SELECT * FROM pg_receipt_item")).isEqualTo(itemBefore);
            assertThat(jdbc.queryForList("""
                    SELECT payment_id,order_no,amount,currency,provider,provider_transaction_id,
                      provider_occurred_at,received_at FROM settlement_pg_transaction ORDER BY id
                    """)).isEqualTo(jdbc.queryForList("""
                    SELECT payment_id,order_no,amount,currency,provider,provider_transaction_id,
                      legacy_provider_occurred_at AS provider_occurred_at,received_at FROM settlement_sale ORDER BY id
                    """));
            assertThat(jdbc.queryForList("SELECT pg_fee_amount FROM settlement_pg_fee ORDER BY id", Long.class)).containsExactly(300L, 200L);
            assertThat(jdbc.queryForList("SELECT DISTINCT recorded_by FROM settlement_pg_fee", String.class)).containsExactly("MIGRATED_V8");
            List<Map<String, Object>> transactionsBefore = jdbc.queryForList("SELECT * FROM settlement_pg_transaction ORDER BY id");
            List<Map<String, Object>> checksumsBeforeV9 = jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank");
            Flyway.configure().dataSource(source).locations(location).load().migrate();
            List<Map<String, Object>> transactionsAfter = jdbc.queryForList("SELECT * FROM settlement_pg_transaction ORDER BY id");
            for (Map<String, Object> row : transactionsAfter) {
                for (String column : List.of("funding_total_amount", "pg_paid_amount", "pg_discount_amount", "provider_store_id",
                        "provider_channel_key", "provider_channel_type", "provider_pg_provider", "benefit_policy_id"))
                    assertThat(row.remove(column)).as(column).isNull();
            }
            assertThat(transactionsAfter).isEqualTo(transactionsBefore);
            assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version<>'9' ORDER BY installed_rank")).isEqualTo(checksumsBeforeV9);
            assertThatThrownBy(() -> jdbc.update("UPDATE settlement_pg_transaction SET funding_total_amount=amount WHERE id=1"))
                    .isInstanceOfSatisfying(UncategorizedSQLException.class, failure -> {
                        assertThat(failure.getSQLException().getErrorCode()).isEqualTo(3819);
                        assertThat(failure.getSQLException().getMessage()).contains("ck_pg_transaction_funding");
                    });
            assertThat(jdbc.queryForMap("SELECT * FROM pg_receipt")).isEqualTo(receiptBefore);
            Flyway.configure().dataSource(source).locations(location).load().validate();
        } finally { root.execute("DROP DATABASE " + schema); }
    }

    private void restoreLegacyColumn(Map<String, Object> row, String column) {
        row.put(column, row.remove("legacy_" + column));
    }

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
