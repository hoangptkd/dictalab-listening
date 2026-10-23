package com.dictalab.service;

import com.dictalab.config.GeminiProviderProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "TEST_GEMINI_REDIS_URL", matches = ".+")
class GeminiQuotaRedisIntegrationTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"gemini","deepseek"})
    @org.junit.jupiter.api.Timeout(30)
    void javaAndPythonReserveTheSameProjectQuotaAndShareCooldown(String platform) throws Exception {
        var uri = URI.create(System.getenv("TEST_GEMINI_REDIS_URL"));
        assertThat(uri.getHost()).isIn("127.0.0.1", "localhost");
        assertThat(uri.getPath()).isEqualTo("/15");
        var config = new RedisStandaloneConfiguration(uri.getHost(), uri.getPort());
        config.setDatabase(15);
        var factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet(); factory.start();
        var redis = new StringRedisTemplate(factory);
        try {
            redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
                connection.serverCommands().flushDb(); return null;
            });
            var source = new GeminiProviderProperties.Provider("interop", "interop-project", "fake-key", platform.equals("gemini") ? "gemini-3.5-flash-lite" : "deepseek-flash",
                    "https://example.test/chat", 2, 100, 100, 2, true, 0);
            var props = new GeminiProviderProperties(List.of(source), 10, 100, true, "redis");
            var quota = new GeminiQuotaCoordinator(props, new SimpleMeterRegistry(), redis, Clock.systemUTC());
            var registry = org.mockito.Mockito.mock(AiProviderRegistry.class);
            org.mockito.BDDMockito.given(registry.databaseEnabled()).willReturn(true);
            org.mockito.BDDMockito.given(registry.properties(platform)).willReturn(props);
            quota.configureRegistry(registry);
            assertThat(quota.reserve(platform,"interop", source.model(), 1).allowed()).isTrue();
            String code = "import os,json; from unittest.mock import patch; from app.config import Settings,GeminiSource; from app.gemini_pool import quota_store; "
                    + "platform=os.environ['TEST_AI_PLATFORM']; raw=json.loads(os.environ['TEST_GEMINI_SOURCE'])[0]; raw['platform']=platform; "
                    + "source=GeminiSource.model_validate(raw); pools={platform:dict(default_model=source.model,global_rpm_limit=10,global_rpd_limit=100,enabled=True)}; "
                    + "patch('app.provider_registry.load_registry',return_value=(pools,[source])).start(); "
                    + "s=Settings(primary_provider=platform,ai_provider_config_source='database',gemini_quota_backend='redis',"
                    + "gemini_quota_redis_url=os.environ['TEST_GEMINI_REDIS_URL']); q=quota_store(s); "
                    + "p=q.select(source.model,1); q.report(p,'429',600)";
            var builder = new ProcessBuilder("python", "-c", code).redirectErrorStream(true);
            builder.environment().put("PYTHONPATH", Path.of("services/ai-generation").toAbsolutePath().toString());
            builder.environment().put("TEST_GEMINI_SOURCE", new tools.jackson.databind.ObjectMapper().writeValueAsString(List.of(source)));
            builder.environment().put("TEST_AI_PLATFORM",platform);
            var process = builder.start();
            boolean completed = process.waitFor(20, TimeUnit.SECONDS);
            if (!completed) process.destroyForcibly();
            assertThat(completed).isTrue();
            assertThat(process.exitValue()).as("Python interop output: %s",
                    new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).isZero();
            assertThat(quota.reserve(platform,"interop", source.model(), 1).retryAfterMs()).isGreaterThan(590000);
            String key = (platform.equals("gemini") ? "{dictalab-gemini-quota}:v2:" : "{dictalab-ai-quota}:v2:deepseek:") + "interop-project:" + source.model() + ":requests";
            assertThat(redis.opsForZSet().size(key)).isEqualTo(2);
        } finally {
            redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
                connection.serverCommands().flushDb(); return null;
            });
            factory.destroy();
        }
    }
}
