package com.dictalab.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

class RequestRateLimitFilterTest {
    @Test void batchWorkConsumesOneTokenPerDeclaredAndVerifiedEvent() {
        long now = System.nanoTime();
        var bucket = new RequestRateLimitFilter.Bucket(600, now);
        for (int i = 0; i < 18; i++) assertThat(bucket.take(600, now, 32)).isTrue();
        assertThat(bucket.take(600, now, 32)).isFalse();
        assertThat(bucket.take(600, now, 24)).isTrue();
        assertThat(bucket.take(600, now, 1)).isFalse();
    }
    @Test void redisBatchLimitReceivesEventWeightAndRejectsInvalidCounts() throws Exception {
        var redis=mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        given(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),anyList(),any(Object[].class))).willReturn(1L);
        var filter=new RequestRateLimitFilter(true,1,new SimpleMeterRegistry(),1,1,10,"redis",redis);
        var request=new MockHttpServletRequest("POST","/api/v1/practice-sessions/test/sync/batches");
        request.addHeader("X-Practice-Events", "8");
        filter.doFilter(request,new MockHttpServletResponse(),(r,s)->{});
        then(redis).should().execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), eq("600"), eq("10"), eq("8"));
        request=new MockHttpServletRequest("POST","/api/v1/practice-sessions/test/sync/batches");
        request.addHeader("X-Practice-Events", "0");
        var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(r,s)->{throw new AssertionError("Invalid batch reached controller");});
        assertThat(response.getStatus()).isEqualTo(400);
    }
    @Test void practiceWritesUseRateLimiterAndGetRemainsAvailable() throws Exception {
        var redis=mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        given(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),anyList(),any(Object[].class))).willReturn(0L);
        var filter=new RequestRateLimitFilter(true,1,new SimpleMeterRegistry(),1,1,10,"redis",redis);
        var response=new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST","/api/v1/practice-sessions"),response,(r,s)->{});
        assertThat(response.getStatus()).isEqualTo(429);
        response=new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST","/api/v1/practice-sessions/test/sentences/1/replay-events"),response,(r,s)->{});
        assertThat(response.getStatus()).isEqualTo(429);
        response=new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET","/api/v1/practice-sessions/test"),response,(r,s)->{});
        assertThat(response.getStatus()).isEqualTo(200);
    }
    @Test void redisFailureBlocksProtectedWritesButNotReads() throws Exception {
        var redis=mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        given(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),anyList(),any(Object[].class)))
                .willThrow(new org.springframework.dao.DataAccessResourceFailureException("private connection details"));
        var filter=new RequestRateLimitFilter(true,1,new SimpleMeterRegistry(),1,1,10,"redis",redis);
        var response=new MockHttpServletResponse();
        filter.doFilter(request(""),response,(r,s)->{throw new AssertionError("Unprotected write");});
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3");
        assertThat(response.getContentAsString()).doesNotContain("private connection details");
        response=new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET","/api/auth/csrf"),response,(r,s)->s.getWriter().write("read"));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("read");
    }
    @Test void downstreamFailuresAreNotMisreportedAsRedisFailures() throws Exception {
        var redis=mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        given(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),anyList(),any(Object[].class))).willReturn(1L);
        var filter=new RequestRateLimitFilter(true,1,new SimpleMeterRegistry(),1,1,10,"redis",redis);
        assertThatThrownBy(()->filter.doFilter(request(""),new MockHttpServletResponse(),(r,s)->{
            throw new org.springframework.dao.DataAccessResourceFailureException("DB failure");
        })).hasMessage("DB failure");
    }
    @Test void entriesAreBoundedAndExpireEvenWithoutNewTraffic() throws Exception {
        var metrics=new SimpleMeterRegistry();var filter=new RequestRateLimitFilter(true,1,metrics,1,1,1);
        filter.doFilter(request(""),new MockHttpServletResponse(),(r,s)->{});
        var another=request("");another.setRemoteAddr("192.0.2.2");var response=new MockHttpServletResponse();
        filter.doFilter(another,response,(r,s)->{});assertThat(response.getStatus()).isEqualTo(429);
        assertThat(metrics.get("dictalab.rate_limit.entries").gauge().value()).isEqualTo(1);
        filter.cleanupAt(System.nanoTime()+java.time.Duration.ofMinutes(11).toNanos());
        response=new MockHttpServletResponse();filter.doFilter(another,response,(r,s)->{});
        assertThat(response.getStatus()).isEqualTo(200);
    }
    @Test void suppliedForwardedHeaderCannotChangeTheActualRemoteAddressKey() throws Exception {
        var filter=new RequestRateLimitFilter(true,1,new SimpleMeterRegistry());
        var first=request("1.2.3.4");var response=new MockHttpServletResponse();
        filter.doFilter(first,response,(r,s)->{});assertThat(response.getStatus()).isEqualTo(200);
        var second=request("9.9.9.9");response=new MockHttpServletResponse();
        filter.doFilter(second,response,(r,s)->{});assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
        second=request("1.2.3.4");second.setRemoteAddr("192.0.2.2");response=new MockHttpServletResponse();
        filter.doFilter(second,response,(r,s)->{});assertThat(response.getStatus()).isEqualTo(200);
    }
    private MockHttpServletRequest request(String forwarded){
        var request=new MockHttpServletRequest("POST","/api/auth/login");request.setRemoteAddr("192.0.2.1");request.addHeader("X-Forwarded-For",forwarded);return request;
    }
}
