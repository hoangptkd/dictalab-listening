package com.dictalab.config;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis is required for JWT session validity, regardless of the rate limit backend. */
@Component("redisAuthHealth")
public class RedisRateLimitHealth implements HealthIndicator {
    private final StringRedisTemplate redis;

    public RedisRateLimitHealth(StringRedisTemplate redis) { this.redis = redis; }

    @Override public Health health() {
        try (var connection = redis.getConnectionFactory().getConnection()) {
            return "PONG".equals(connection.ping()) ? Health.up().build() : Health.down().build();
        } catch (RuntimeException unavailable) {
            // No connection URL, credential, or raw exception in health output.
            return Health.down().build();
        }
    }
}
