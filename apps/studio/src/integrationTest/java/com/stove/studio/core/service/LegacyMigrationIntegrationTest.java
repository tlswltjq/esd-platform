package com.stove.studio.core.service;

import static org.assertj.core.api.Assertions.*;

import com.stove.common.testcontainers.SharedContainers;
import com.stove.studio.StudioIntegrationTestBase;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import java.nio.file.Path;
import java.util.UUID;

class LegacyMigrationIntegrationTest extends StudioIntegrationTestBase {
    @Test
    void migratesPublishedSnapshotAndReservesUnmigratedLegacyIds() {
        MySQLContainer<? extends MySQLContainer<?>> mysql = SharedContainers.MYSQL;
        String schema = "studio_migration_" + UUID.randomUUID().toString().replace("-", "");
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        JdbcTemplate root =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                server
                                        + mysql.getDatabaseName()
                                        + "?useSSL=false&allowPublicKeyRetrieval=true",
                                "root",
                                mysql.getPassword()));
        root.execute("CREATE DATABASE " + schema);
        try {
            DriverManagerDataSource data =
                    new DriverManagerDataSource(
                            server + schema + "?useSSL=false&allowPublicKeyRetrieval=true",
                            "root",
                            mysql.getPassword());
            String migrations =
                    "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath();
            Flyway.configure().dataSource(data).locations(migrations).target("22").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(data);
            jdbc.update(
                    "INSERT INTO workspace(id,owner_subject,status,created_at,updated_at) VALUES"
                            + " (100001,'legacy-owner','ACTIVE',NOW(),NOW())");
            jdbc.update(
                    "INSERT INTO"
                        + " game_project(id,product_code,title,workspace_id,price,currency,self_rated,status,created_at,updated_at)"
                        + " VALUES"
                        + " (7,'LEGACY','Original',100001,1000,'KRW',0,'APPROVED',NOW(),NOW())");
            jdbc.update(
                    "INSERT INTO"
                        + " game_build(id,game_id,version,build_number,platform,architecture,file_size,checksum,actual_checksum,storage_path,idempotency_key,status,created_at,updated_at)"
                        + " VALUES"
                        + " (61,7,'1','1','WINDOWS','X86_64',100,?,?,'s3://stove-builds/legacy.zip','legacy-61','VALIDATED',NOW(),NOW())",
                    "a".repeat(64),
                    "a".repeat(64));
            jdbc.update(
                    "INSERT INTO"
                        + " game_build(id,game_id,version,build_number,platform,architecture,file_size,checksum,storage_path,idempotency_key,status,created_at,updated_at)"
                        + " VALUES"
                        + " (101,7,'bad','bad','WINDOWS','X86_64',100,?,'s3://stove-builds/bad.zip','legacy-101','FAILED',NOW(),NOW())",
                    "b".repeat(64));
            jdbc.update(
                    """
INSERT INTO store_page_revision(id,game_id,revision_no,title,short_description,platform,minimum_requirements,
detailed_description,localizations_json,genres_json,tags_json,developer,publisher,screenshots_json,trailers_json,
icon_url,cover_url,supported_languages_json,recommended_requirements,features_json,support_url,privacy_policy_url,eula_url,
sales_countries_json,prices_json,status,created_at,updated_at)
VALUES (1,7,1,'Approved title','Approved summary','WINDOWS','','Approved content','{}','[]','[]','','','[]','[]',
'https://example.com/image.png','https://example.com/image.png','[]','','[]','','','','["KR"]','{}','PUBLISHED',NOW(),NOW())
""");
            jdbc.update(
                    "INSERT INTO"
                        + " pricing_revision(id,game_id,revision_no,country,currency,price,created_at,updated_at)"
                        + " VALUES (1,7,1,'KR','KRW',1000,NOW(),NOW())");
            jdbc.update(
                    "INSERT INTO"
                        + " submission(id,game_id,workspace_id,sequence_no,metadata_revision_id,pricing_revision_id,rating_revision_id,build_id,status,created_at,updated_at)"
                        + " VALUES (1,7,100001,1,1,1,1,61,'RELEASED',NOW(),NOW())");
            jdbc.update(
                    "INSERT INTO"
                        + " game_release(id,game_id,workspace_id,submission_id,build_id,metadata_revision_id,pricing_revision_id,rating_revision_id,status,published_at,created_at,updated_at)"
                        + " VALUES (51,7,100001,1,61,1,1,1,'PUBLISHED',NOW(),NOW(),NOW())");
            jdbc.update(
                    "INSERT INTO"
                        + " game_release(id,game_id,workspace_id,submission_id,build_id,metadata_revision_id,pricing_revision_id,rating_revision_id,status,created_at,updated_at)"
                        + " VALUES (99,7,100001,1,101,1,1,1,'SMOKE_TEST_FAILED',NOW(),NOW())");
            jdbc.update(
                    """
INSERT INTO store_page_revision(id,game_id,revision_no,title,short_description,platform,minimum_requirements,
detailed_description,localizations_json,genres_json,tags_json,developer,publisher,screenshots_json,trailers_json,
icon_url,cover_url,supported_languages_json,recommended_requirements,features_json,support_url,privacy_policy_url,eula_url,
sales_countries_json,prices_json,status,created_at,updated_at)
SELECT 2,game_id,2,'Draft title',short_description,platform,minimum_requirements,
'Draft content',localizations_json,genres_json,tags_json,developer,publisher,screenshots_json,trailers_json,
icon_url,cover_url,supported_languages_json,recommended_requirements,features_json,support_url,privacy_policy_url,eula_url,
sales_countries_json,prices_json,'DRAFT',NOW(),NOW() FROM store_page_revision WHERE id=1
""");
            jdbc.update(
                    "INSERT INTO"
                        + " pricing_revision(id,game_id,revision_no,country,currency,price,created_at,updated_at)"
                        + " VALUES (2,7,2,'KR','KRW',2000,NOW(),NOW())");
            jdbc.update(
                    "INSERT INTO"
                        + " game_build(id,game_id,version,build_number,platform,architecture,file_size,checksum,actual_checksum,storage_path,idempotency_key,status,created_at,updated_at)"
                        + " VALUES"
                        + " (62,7,'2','2','WINDOWS','X86_64',200,?,?,'s3://stove-builds/draft.zip','legacy-62','VALIDATED',NOW(),NOW())",
                    "c".repeat(64),
                    "c".repeat(64));
            Flyway.configure().dataSource(data).locations(migrations).load().migrate();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT owner_subject FROM studio_project WHERE id=7",
                                    String.class))
                    .isEqualTo("legacy-owner");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT current_release_request_id FROM studio_project WHERE"
                                            + " id=7",
                                    Long.class))
                    .isEqualTo(51L);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM studio_release_request WHERE id=51",
                                    String.class))
                    .isEqualTo("COMPLETED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT title FROM studio_project WHERE id=7", String.class))
                    .isEqualTo("Draft title");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT price FROM studio_project WHERE id=7", Long.class))
                    .isEqualTo(2000L);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT current_build_id FROM studio_project WHERE id=7",
                                    Long.class))
                    .isEqualTo(62L);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT snapshot FROM studio_review_request WHERE id=51",
                                    String.class))
                    .contains("Approved title", "Approved content", "ownerSubject", "legacy-owner")
                    .doesNotContain("sellerId");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM information_schema.columns WHERE"
                                        + " table_schema=DATABASE() AND table_name='studio_project'"
                                        + " AND column_name='seller_id'",
                                    Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM studio_build", Integer.class))
                    .isEqualTo(2);
            jdbc.update(
                    "INSERT INTO"
                        + " studio_release_request(project_id,review_request_id,content_version,status,created_at,updated_at)"
                        + " VALUES (7,51,52,'REQUESTED',NOW(),NOW())");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT MAX(id) FROM studio_release_request", Long.class))
                    .isGreaterThan(99L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_release", Integer.class))
                    .isEqualTo(2);
        } finally {
            root.execute("DROP DATABASE " + schema);
        }
    }
}
