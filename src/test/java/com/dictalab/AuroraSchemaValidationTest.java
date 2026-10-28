package com.dictalab;

import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class AuroraSchemaValidationTest {
    @Test void runtimeValidatesWithoutSchemaWritesAndRejectsPendingOrChangedMigrations(@TempDir Path migrations) throws Exception {
        String url = "jdbc:h2:mem:aurora-validation;DB_CLOSE_DELAY=-1";
        Path first = migrations.resolve("V1__initial.sql");
        Files.writeString(first, "CREATE TABLE business_data(id INTEGER PRIMARY KEY);");
        Flyway owner = Flyway.configure().dataSource(url, "sa", "").locations("filesystem:" + migrations).load();
        owner.migrate();
        var db = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        db.execute("CREATE USER runtime PASSWORD 'synthetic-test-password'");
        db.execute("GRANT SELECT ON \"flyway_schema_history\" TO runtime");
        Flyway runtime = Flyway.configure().dataSource("jdbc:h2:mem:aurora-validation", "runtime", "synthetic-test-password")
            .locations("filesystem:" + migrations).load();
        var strategy = new DictaLabApplication().auroraSchemaValidation();
        assertThatCode(() -> strategy.migrate(runtime)).doesNotThrowAnyException();
        Path pending = migrations.resolve("V2__pending.sql");
        Files.writeString(pending, "CREATE TABLE must_not_be_created(id INTEGER);");
        assertThatThrownBy(() -> strategy.migrate(runtime)).isInstanceOf(RuntimeException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='MUST_NOT_BE_CREATED'", Integer.class)).isZero();
        Files.delete(pending);
        Files.writeString(first, "CREATE TABLE changed_data(id INTEGER PRIMARY KEY);");
        assertThatThrownBy(() -> strategy.migrate(runtime)).isInstanceOf(RuntimeException.class);
    }
}
