package com.stove.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stove.common.testcontainers.SharedContainers;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

class StudioGenreMigrationIntegrationTest {
    @Test void relationMigrationPreservesRowsAndEnforcesUniqueCodesAndParentIdentity() {
        MySQLContainer<?> mysql = SharedContainers.MYSQL;
        mysql.start();
        String server = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true";
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(server + mysql.getDatabaseName() + options, "root", mysql.getPassword()));
        String schema = "genre_migration_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE DATABASE " + schema);
        try {
            DriverManagerDataSource source = new DriverManagerDataSource(server + schema + options, "root", mysql.getPassword());
            String location = "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath();
            Flyway.configure().dataSource(source).locations(location).target("26").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO studio_project(id,product_code,owner_subject,title,content,content_version,created_at,updated_at) VALUES(1,'genre-draft','creator','Draft','',1,'2026-11-03','2026-11-03')");
            Map<String, Object> before = jdbc.queryForMap("SELECT * FROM studio_project");
            Flyway.configure().dataSource(source).locations(location).load().migrate();
            assertThat(jdbc.queryForMap("SELECT * FROM studio_project")).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM studio_project_genre", Long.class)).isZero();
            jdbc.update("INSERT INTO studio_project_genre VALUES(1,'ACTION')");
            assertThatThrownBy(() -> jdbc.update("INSERT INTO studio_project_genre VALUES(1,'ACTION')")).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO studio_project_genre VALUES(2,'RPG')")).isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update("INSERT INTO studio_project_genre VALUES(1,'RPG')");
            jdbc.update("DELETE FROM studio_project");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM studio_project_genre", Long.class)).isZero();
        } finally { root.execute("DROP DATABASE " + schema); }
    }
}
