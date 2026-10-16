package com.dictalab.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "dictalab.testcontainers", matches = "true")
@SpringBootTest
class ReliabilityInfrastructureTest {
    @Autowired JdbcTemplate db;
    @Autowired StringRedisTemplate redis;
    @Autowired RabbitTemplate rabbit;

    @Test void suiteUsesThreeDisposableContainersAndRealPostgresMigrations() throws Exception {
        for (String resource : new String[]{"postgres", "redis", "rabbit"})
            assertThat(System.getProperty("dictalab.testcontainers." + resource + "-id")).hasSize(64);
        try (var connection = db.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
        assertThat(db.queryForObject("SELECT current_database()", String.class)).isEqualTo("dictalab_test");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(37);
        try (var connection = redis.getConnectionFactory().getConnection()) {
            assertThat(connection.ping()).isEqualTo("PONG");
        }
        String product = rabbit.execute(channel -> channel.getConnection().getServerProperties().get("product").toString());
        assertThat(product).isEqualTo("RabbitMQ");
    }
}
