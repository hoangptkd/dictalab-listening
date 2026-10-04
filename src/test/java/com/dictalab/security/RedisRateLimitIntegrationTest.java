package com.dictalab.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Run with scripts/test-messaging.ps1 against the disposable Redis container, never a shared store. */
@EnabledIfEnvironmentVariable(named="DICTALAB_MESSAGING_TEST",matches="true")
class RedisRateLimitIntegrationTest {
    private LettuceConnectionFactory connection;
    private StringRedisTemplate redis;
    private final String actor="test-"+UUID.randomUUID();
    private final String key="{dictalab-rate}:v1:auth:ip:"+actor;
    private static final String INDEX="{dictalab-rate}:v1:index";

    @BeforeEach void connect() {
        var configuration=new RedisStandaloneConfiguration("127.0.0.1",Integer.parseInt(System.getenv("TEST_REDIS_PORT")));
        configuration.setDatabase(15);
        connection=new LettuceConnectionFactory(configuration);connection.afterPropertiesSet();connection.start();
        redis=new StringRedisTemplate(connection);redis.afterPropertiesSet();
    }
    @AfterEach void close() {
        if(redis!=null){redis.delete(key);redis.delete(key+":other");redis.opsForZSet().remove(INDEX,key,key+":other");}
        if(connection!=null)connection.destroy();
    }
    private RequestRateLimitFilter filter(int capacity,int maximum) {
        return new RequestRateLimitFilter(true,capacity,new SimpleMeterRegistry(),capacity,capacity,maximum,"redis",redis);
    }
    private int request(RequestRateLimitFilter filter,String address) throws Exception {
        var request=new MockHttpServletRequest("POST","/api/auth/login");request.setRemoteAddr(address);
        var response=new MockHttpServletResponse();filter.doFilter(request,response,(r,s)->{});return response.getStatus();
    }
    @Test void twoInstancesShareOneAtomicBudgetAndKeysExpire() throws Exception {
        var first=filter(1,100);var second=filter(1,100);
        var executor=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);
        try {
            var futures=new java.util.ArrayList<Future<Integer>>();
            for(int i=0;i<20;i++){
                var selected=i%2==0?first:second;
                futures.add(executor.submit(()->{start.await(5,TimeUnit.SECONDS);return request(selected,actor);}));
            }
            start.countDown();int allowed=0;
            for(var future:futures){int status=future.get(10,TimeUnit.SECONDS);assertThat(status).isIn(200,429);if(status==200)allowed++;}
            assertThat(allowed).isEqualTo(1);
            assertThat(redis.getExpire(key)).isBetween(590L,600L);
            assertThat(redis.getExpire(INDEX)).isBetween(590L,600L);
        } finally {executor.shutdownNow();}
    }
    @Test void redisClockRefillsBudgetAndRejectsNewActorsAtCapacity() throws Exception {
        int maximum=Math.toIntExact(redis.opsForZSet().size(INDEX))+1;
        var first=filter(1,maximum);
        assertThat(request(first,actor)).isEqualTo(200);
        assertThat(request(first,actor+":other")).isEqualTo(429);
        redis.opsForHash().put(key,"tokens","0");redis.opsForHash().put(key,"touched","0");
        assertThat(request(filter(1,maximum),actor)).isEqualTo(200);
        assertThat(redis.hasKey(key+":other")).isFalse();
    }
}
