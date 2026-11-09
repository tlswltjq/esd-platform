package com.stove.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.testcontainers.SharedContainers;
import java.nio.file.Path;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

class SettlementMigrationIntegrationTest extends SettlementIntegrationTestBase {
    @Test void v8ThroughV11PreserveClosedAllocationsReportsAndNeverInventFundingOrRefunds() {
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
            Flyway.configure().dataSource(source).locations(location).target("9").load().migrate();
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
            List<Map<String, Object>> oldSales = jdbc.queryForList("SELECT * FROM settlement_sale ORDER BY id");
            List<Map<String, Object>> oldTransactions = jdbc.queryForList("SELECT * FROM settlement_pg_transaction ORDER BY id");
            List<Map<String, Object>> oldFees = jdbc.queryForList("SELECT * FROM settlement_pg_fee ORDER BY id");
            Map<String, Object> oldMonth = jdbc.queryForMap("SELECT * FROM seller_monthly_settlement");
            List<Map<String, Object>> oldChecksums = jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank");
            Flyway.configure().dataSource(source).locations(location).target("10").load().migrate();
            assertThat(jdbc.queryForList("SELECT * FROM settlement_sale ORDER BY id")).isEqualTo(oldSales);
            assertThat(jdbc.queryForList("SELECT * FROM settlement_pg_transaction ORDER BY id")).isEqualTo(oldTransactions);
            assertThat(jdbc.queryForList("SELECT * FROM settlement_pg_fee ORDER BY id")).isEqualTo(oldFees);
            Map<String, Object> newMonth = jdbc.queryForMap("SELECT * FROM seller_monthly_settlement");
            for (String column : List.of("refund_amount", "refunded_platform_fee_amount", "refunded_seller_payout_amount", "refund_count"))
                assertThat(((Number) newMonth.remove(column)).longValue()).as(column).isZero();
            assertThat(newMonth).isEqualTo(oldMonth);
            assertThat(jdbc.queryForMap("SELECT * FROM pg_receipt")).isEqualTo(receiptBefore);
            assertThat(jdbc.queryForMap("SELECT * FROM pg_receipt_item")).isEqualTo(itemBefore);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_refund", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_refund_cancellation", Long.class)).isZero();
            assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version<>'10' ORDER BY installed_rank")).isEqualTo(oldChecksums);
            List<Map<String,Object>> v10Checksums=jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank");
            Map<String,Object> v10Month=jdbc.queryForMap("SELECT * FROM seller_monthly_settlement");
            Flyway.configure().dataSource(source).locations(location).target("11").load().migrate();
            assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version<>'11' ORDER BY installed_rank")).isEqualTo(v10Checksums);
            assertThat(jdbc.queryForList("SELECT * FROM settlement_sale ORDER BY id")).isEqualTo(oldSales);
            assertThat(jdbc.queryForList("SELECT * FROM settlement_pg_transaction ORDER BY id")).isEqualTo(oldTransactions);
            assertThat(jdbc.queryForList("SELECT * FROM settlement_pg_fee ORDER BY id")).isEqualTo(oldFees);
            assertThat(jdbc.queryForMap("SELECT * FROM seller_monthly_settlement")).isEqualTo(v10Month);
            assertThat(jdbc.queryForMap("SELECT * FROM pg_receipt")).isEqualTo(receiptBefore);
            assertThat(jdbc.queryForMap("SELECT * FROM pg_receipt_item")).isEqualTo(itemBefore);
            for(String table:List.of("settlement_pg_statement","settlement_pg_statement_page","settlement_pg_statement_entry","settlement_pg_statement_item"))
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).as(table).isZero();
            jdbc.update("""
                    INSERT INTO settlement_pg_statement(merchant_id,environment,portone_store_id,portone_channel_key,identity_mapping_verified,
                      date_type,base_date,fingerprint,total_amount,total_fee,total_vat,total_fee_vat_sum,record_count,page_count,
                      received_at,collected_by,status)
                    VALUES('historical-merchant','LIVE','store','channel',false,'DUE','2026-10-01','historical-fingerprint',
                      10000,-300,-30,-330,1,1,'2026-10-02','admin','PENDING')
                    """);
            jdbc.update("INSERT INTO settlement_pg_statement_page(statement_id,page_position,request_cursor,next_cursor,response_body) VALUES(1,0,NULL,'','historical-raw-page')");
            jdbc.update("""
                    INSERT INTO settlement_pg_statement_entry(merchant_id,environment,transaction_type,transaction_id,pay_token,order_no,
                      amount,fee,vat,fee_vat_sum,pay_method,due_date,received_at)
                    VALUES('historical-merchant','LIVE','PAY','historical-pay','token','payment',10000,-300,-30,-330,'CARD','2026-10-01','2026-10-02')
                    """);
            jdbc.update("INSERT INTO settlement_pg_statement_item(statement_id,item_position,entry_id,check_code) VALUES(1,0,1,'MISSING_SALE')");
            List<String> preservedTables=List.of("settlement_sale","settlement_pg_transaction","settlement_pg_fee","seller_monthly_settlement",
                    "pg_receipt","pg_receipt_item","settlement_pg_statement","settlement_pg_statement_page","settlement_pg_statement_entry","settlement_pg_statement_item");
            Map<String,List<Map<String,Object>>> preserved=new HashMap<>();
            for(String table:preservedTables) preserved.put(table,jdbc.queryForList("SELECT * FROM "+table));
            List<Map<String,Object>> beforeV12=jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank");
            Flyway.configure().dataSource(source).locations(location).target("12").load().migrate();
            assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version<>'12' ORDER BY installed_rank")).isEqualTo(beforeV12);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_pg_cost_posting",Long.class)).isZero();
            for(String table:preservedTables) assertThat(jdbc.queryForList("SELECT * FROM "+table)).as(table).isEqualTo(preserved.get(table));
            Flyway.configure().dataSource(source).locations(location).load().migrate();
            for(String table:preservedTables) assertThat(jdbc.queryForList("SELECT * FROM "+table)).as(table).isEqualTo(preserved.get(table));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_seller_carryover",Long.class)).isZero();
            Flyway.configure().dataSource(source).locations(location).load().validate();
        } finally { root.execute("DROP DATABASE " + schema); }
    }

    private void restoreLegacyColumn(Map<String, Object> row, String column) {
        row.put(column, row.remove("legacy_" + column));
    }

    /** 결정 121·130: V13은 새 이월 원장만 만들고 옛 CLOSED 월을 채우거나 바꾸지 않는다. */
    @Test void v13AddsAnEmptyCarryLedgerAndKeepsClosedMonthsAsTheyWere() {
        MySQLContainer<?> mysql=SharedContainers.MYSQL;
        String schema="settlement_v13_"+UUID.randomUUID().toString().replace("-","");
        String server="jdbc:mysql://"+mysql.getHost()+":"+mysql.getMappedPort(3306)+"/";
        JdbcTemplate root=new JdbcTemplate(new DriverManagerDataSource(server+mysql.getDatabaseName()+"?useSSL=false&allowPublicKeyRetrieval=true","root",mysql.getPassword()));
        root.execute("CREATE DATABASE "+schema);
        try {
            DriverManagerDataSource source=new DriverManagerDataSource(server+schema+"?useSSL=false&allowPublicKeyRetrieval=true","root",mysql.getPassword());JdbcTemplate jdbc=new JdbcTemplate(source);
            String location="filesystem:"+Path.of("src/main/resources/db/migration").toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target("12").load().migrate();
            jdbc.update("""
                    INSERT INTO seller_monthly_settlement(seller_subject,settlement_month,status,paid_amount,platform_fee_amount,seller_payout_amount,
                      record_count,refund_amount,refunded_platform_fee_amount,refunded_seller_payout_amount,refund_count,created_at,updated_at,closed_at)
                    VALUES('seller','2026-09','CLOSED',9000,900,8100,1,0,0,0,0,'2026-09-01','2026-10-02','2026-10-02'),
                      ('seller','2026-10','CLOSED',5000,500,4500,1,9000,900,8100,1,'2026-10-01','2026-11-02','2026-11-02'),
                      ('seller','2026-11','OPEN',3000,300,2700,1,0,0,0,0,'2026-11-01','2026-11-15',NULL)
                    """);
            List<Map<String,Object>> months=jdbc.queryForList("SELECT * FROM seller_monthly_settlement ORDER BY id");
            List<Map<String,Object>> beforeV13=jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank");
            Flyway.configure().dataSource(source).locations(location).target("13").load().migrate();
            assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version<>'13' ORDER BY installed_rank")).isEqualTo(beforeV13);
            assertThat(jdbc.queryForList("SELECT * FROM seller_monthly_settlement ORDER BY id")).isEqualTo(months);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_seller_carryover",Long.class)).isZero();
            String insert="INSERT INTO settlement_seller_carryover(seller_subject,settlement_month,net_seller_payout_amount,carried_in,payable,carried_out,source_month,created_at) VALUES";
            jdbc.update(insert+"('seller','2026-11',2700,-3600,0,-900,'2026-10','2026-12-02')");
            jdbc.update(insert+"('seller','2026-12',1800,-900,900,0,'2026-11','2027-01-02')");
            jdbc.update(insert+"('seller','2026-09',8100,0,8100,0,NULL,'2026-10-02')");
            assertThatThrownBy(() -> jdbc.update(insert+"('seller','2026-11',2700,-3600,0,-900,'2026-10','2026-12-03')"))
                    .as("같은 판매자·월 두 번째 행").isInstanceOf(DuplicateKeyException.class);
            Map<String,String> rejected=Map.of(
                    "('seller','2027-01',100,1,101,0,'2026-12','2027-02-01')","ck_seller_carryover_amount",
                    "('seller','2027-01',-100,0,-100,0,NULL,'2027-02-01')","ck_seller_carryover_amount",
                    "('seller','2027-01',100,0,0,100,NULL,'2027-02-01')","ck_seller_carryover_amount",
                    "('seller','2027-01',100,-50,60,-10,'2026-12','2027-02-01')","ck_seller_carryover_amount",
                    "('seller','2027-01',100,0,90,0,NULL,'2027-02-01')","ck_seller_carryover_amount",
                    "('seller','2027-01',100,-50,50,0,NULL,'2027-02-01')","ck_seller_carryover_source",
                    "('seller','2027-01',100,0,100,0,'2027-01','2027-02-01')","ck_seller_carryover_source",
                    "('seller','2027-01',100,0,100,0,'2027-02','2027-02-01')","ck_seller_carryover_source");
            for(Map.Entry<String,String> bad:rejected.entrySet())
                assertThatThrownBy(() -> jdbc.update(insert+bad.getKey())).as(bad.getKey())
                        .isInstanceOfSatisfying(UncategorizedSQLException.class, failure -> {
                            assertThat(failure.getSQLException().getErrorCode()).isEqualTo(3819);
                            assertThat(failure.getSQLException().getMessage()).contains(bad.getValue());
                        });
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_seller_carryover",Long.class)).isEqualTo(3);
            Flyway.configure().dataSource(source).locations(location).target("13").load().validate();
        } finally { root.execute("DROP DATABASE "+schema); }
    }
    /**
     * D-045·D-046: V14는 빈 판매자 잠금 테이블만 만들고, V15는 이월 원장에 (판매자, 출처 월) 유일 제약을 더한다.
     * 옛 원장 행이 출처를 겹쳐 쓰지 않았으면 행을 그대로 두고 적용되고, 겹쳐 썼으면 V15만 실패하고 원장은 바뀌지 않는다.
     */
    @Test void v14AndV15SerializeSellersAndRejectAReusedCarrySourceWithoutChangingTheLedger() {
        MySQLContainer<?> mysql=SharedContainers.MYSQL;
        String server="jdbc:mysql://"+mysql.getHost()+":"+mysql.getMappedPort(3306)+"/";
        JdbcTemplate root=new JdbcTemplate(new DriverManagerDataSource(server+mysql.getDatabaseName()+"?useSSL=false&allowPublicKeyRetrieval=true","root",mysql.getPassword()));
        String insert="INSERT INTO settlement_seller_carryover(seller_subject,settlement_month,net_seller_payout_amount,carried_in,payable,carried_out,source_month,created_at) VALUES";
        String location="filesystem:"+Path.of("src/main/resources/db/migration").toAbsolutePath();
        for(boolean reused:List.of(false,true)) {
            String schema="settlement_v15_"+UUID.randomUUID().toString().replace("-","");
            root.execute("CREATE DATABASE "+schema);
            try {
                DriverManagerDataSource source=new DriverManagerDataSource(server+schema+"?useSSL=false&allowPublicKeyRetrieval=true","root",mysql.getPassword());JdbcTemplate jdbc=new JdbcTemplate(source);
                Flyway.configure().dataSource(source).locations(location).target("13").load().migrate();
                jdbc.update(insert+"('seller','2026-08',8100,0,8100,0,NULL,'2026-09-02'),('seller','2026-10',-9000,0,0,-9000,'2026-08','2026-11-02'),"
                        +"('seller','2026-12',20000,-9000,11000,0,'2026-10','2027-01-02'),('other','2026-12',100,0,100,0,'2026-10','2027-01-02')");
                if(reused) jdbc.update(insert+"('seller','2026-11',0,-9000,0,-9000,'2026-10','2027-01-03')");
                List<Map<String,Object>> ledger=jdbc.queryForList("SELECT * FROM settlement_seller_carryover ORDER BY id");
                if(!reused) {
                    Flyway.configure().dataSource(source).locations(location).load().migrate();
                    assertThat(jdbc.queryForList("SELECT * FROM settlement_seller_carryover ORDER BY id")).isEqualTo(ledger);
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_seller_lock",Long.class)).isZero();
                    assertThatThrownBy(() -> jdbc.update(insert+"('seller','2026-11',0,-9000,0,-9000,'2026-10','2027-01-03')"))
                            .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uk_seller_carryover_source");
                    jdbc.update(insert+"('seller','2026-07',0,0,0,0,NULL,'2026-08-02')");
                    Flyway.configure().dataSource(source).locations(location).load().validate();
                } else {
                    assertThatThrownBy(() -> Flyway.configure().dataSource(source).locations(location).load().migrate())
                            .as("겹친 출처가 있으면 V15가 실패한다").hasMessageContaining("uk_seller_carryover_source");
                    assertThat(jdbc.queryForList("SELECT * FROM settlement_seller_carryover ORDER BY id")).isEqualTo(ledger);
                    assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1",String.class)).containsExactly("14");
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=? AND index_name='uk_seller_carryover_source'",Long.class,schema)).isZero();
                }
            } finally { root.execute("DROP DATABASE "+schema); }
        }
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
