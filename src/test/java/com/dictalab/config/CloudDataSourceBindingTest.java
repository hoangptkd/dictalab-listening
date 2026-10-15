package com.dictalab.config;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.*;

class CloudDataSourceBindingTest {
    @Test
    void nestedConnectionTimeoutEnvironmentNameStartsAndSealsPoolDuringBinding() {
        try (var dataSource = dataSource()) {
            assertThatThrownBy(() -> bind(dataSource, Map.of(
                    "SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT", "3000")))
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("The configuration of the pool is sealed once started. Use HikariConfigMXBean for runtime changes.");
            assertThat(dataSource.getHikariPoolMXBean()).isNotNull();
        }
    }

    @Test
    void canonicalEnvironmentNamesBindWithoutStartingPoolAndPreserveSqlDeadline() throws Exception {
        try (var dataSource = dataSource()) {
            bind(dataSource, Map.of(
                    "SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT", "3000",
                    "SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE", "5",
                    "SPRING_DATASOURCE_HIKARI_MINIMUMIDLE", "0"));
            assertThat(dataSource.getHikariPoolMXBean()).isNull();
            assertThat(dataSource.getMaximumPoolSize()).isEqualTo(5);
            assertThat(dataSource.getMinimumIdle()).isZero();
            assertThat(dataSource.getConnectionTimeout()).isEqualTo(3000);
            assertThat(dataSource.getConnectionInitSql()).isEqualTo("SET LOCK_TIMEOUT 3000");
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT 1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
        }
    }

    private HikariDataSource dataSource() {
        var dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:cloud-binding");
        dataSource.setUsername("sa");
        dataSource.setMaximumPoolSize(1);
        dataSource.setMinimumIdle(0);
        return dataSource;
    }

    private void bind(HikariDataSource dataSource, Map<String, Object> variables) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        environment.getPropertySources().addLast(new MapPropertySource("yaml", Map.of(
                "spring.datasource.hikari.connection-init-sql", "SET LOCK_TIMEOUT 3000")));
        new Binder(ConfigurationPropertySources.get(environment))
                .bind("spring.datasource.hikari", Bindable.ofInstance(dataSource));
    }
}
