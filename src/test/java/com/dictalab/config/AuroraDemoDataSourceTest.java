package com.dictalab.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AuroraDemoDataSourceTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withPropertyValues("spring.config.location=file:src/main/resources/application.yml")
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class));

    @Test
    void iamProfileReplacesNeonCredentialsAndRetainsSessionDeadlinesWithoutOpeningConnections() {
        context.withPropertyValues("spring.profiles.active=aurora-demo",
                "AURORA_DB_HOST=demo.cluster-example.ap-southeast-1.rds.amazonaws.com",
                "DB_URL=jdbc:postgresql://neon.invalid/production", "DB_USERNAME=production",
                "DB_PASSWORD=must-not-be-used").run(app -> {
                    assertThat(app).hasNotFailed();
                    var pool = app.getBean(HikariDataSource.class);
                    assertThat(pool.getHikariPoolMXBean()).isNull();
                    assertThat(pool.getJdbcUrl()).startsWith("jdbc:aws-wrapper:postgresql://demo.");
                    assertThat(pool.getUsername()).isEqualTo("dictalab_demo");
                    assertThat(pool.getPassword()).isNullOrEmpty();
                    assertThat(pool.getDriverClassName()).isEqualTo("software.amazon.jdbc.Driver");
                    assertThat(pool.getDataSourceProperties()).containsEntry("sslmode", "verify-full")
                            .containsEntry("wrapperPlugins", "iam");
                    assertThat(pool.getConnectionTimeout()).isGreaterThanOrEqualTo(60000);
                    assertThat(pool.getConnectionInitSql()).contains("statement_timeout=15000", "lock_timeout=3000");
                });
    }

    @Test
    void normalProfileKeepsTheExistingPostgresqlDriver() {
        context.withPropertyValues("spring.profiles.active=default", "DB_URL=jdbc:postgresql://localhost:15433/test",
                "DB_USERNAME=test", "DB_PASSWORD=test-only").run(app -> {
                    assertThat(app).hasNotFailed();
                    var pool = app.getBean(HikariDataSource.class);
                    assertThat(pool.getDriverClassName()).isEqualTo("org.postgresql.Driver");
                    assertThat(pool.getJdbcUrl()).isEqualTo("jdbc:postgresql://localhost:15433/test");
                });
    }
}
