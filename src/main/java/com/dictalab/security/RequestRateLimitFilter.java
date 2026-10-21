package com.dictalab.security;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/** Token buckets in memory or Redis. Remote addresses come only from the trusted proxy configuration. */
@Component
public class RequestRateLimitFilter extends OncePerRequestFilter {
    private static final DefaultRedisScript<Long> TAKE_REDIS_TOKEN = new DefaultRedisScript<>("""
            local clock = redis.call('TIME')
            local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
            local capacity = tonumber(ARGV[1])
            local maximum = tonumber(ARGV[2])
            local ttl = 600000
            local saved = redis.call('HMGET', KEYS[1], 'tokens', 'touched')
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
            if not saved[1] and redis.call('ZCARD', KEYS[2]) >= maximum then return 0 end
            local tokens = capacity
            if saved[1] then
                tokens = math.min(capacity, tonumber(saved[1]) + math.max(0, now - tonumber(saved[2])) * capacity / 60000)
            end
            local allowed = 0
            if tokens >= 1 then tokens = tokens - 1; allowed = 1 end
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'touched', now)
            redis.call('PEXPIRE', KEYS[1], ttl)
            redis.call('ZADD', KEYS[2], now + ttl, KEYS[1])
            redis.call('PEXPIRE', KEYS[2], ttl)
            return allowed
            """, Long.class);
    private final ConcurrentHashMap<String,Bucket> buckets=new ConcurrentHashMap<>();
    private final boolean enabled;
    private final String backend;
    private final StringRedisTemplate redis;
    private final MeterRegistry metrics;
    private final int authBurst;
    private final int aiBurst;
    private final int uploadBurst;
    private final int maximumEntries;
    @org.springframework.beans.factory.annotation.Autowired
    public RequestRateLimitFilter(@Value("${dictalab.rate-limit.enabled:true}") boolean enabled,
            @Value("${dictalab.rate-limit.auth-burst:120}") int authBurst,MeterRegistry metrics,
            @Value("${dictalab.rate-limit.ai-burst:60}") int aiBurst,
            @Value("${dictalab.rate-limit.upload-burst:20}") int uploadBurst,
            @Value("${dictalab.rate-limit.maximum-entries:20000}") int maximumEntries,
            @Value("${dictalab.rate-limit.backend:memory}") String backend,
            org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redis){
        this(enabled,authBurst,metrics,aiBurst,uploadBurst,maximumEntries,backend,redis.getIfAvailable());
    }
    public RequestRateLimitFilter(boolean enabled,int authBurst,MeterRegistry metrics,int aiBurst,int uploadBurst,
            int maximumEntries,String backend,StringRedisTemplate redis){
        if(authBurst<1 || aiBurst<1 || uploadBurst<1 || maximumEntries<1)throw new IllegalArgumentException("Invalid rate limit");
        if(!"memory".equalsIgnoreCase(backend) && !"redis".equalsIgnoreCase(backend))throw new IllegalArgumentException("Invalid rate limit backend");
        this.enabled=enabled;this.authBurst=authBurst;this.metrics=metrics;
        this.aiBurst=aiBurst;this.uploadBurst=uploadBurst;this.maximumEntries=maximumEntries;
        this.backend=backend.toLowerCase(java.util.Locale.ROOT);this.redis=redis;
        metrics.gauge("dictalab.rate_limit.entries",buckets,java.util.Map::size);
    }
    public RequestRateLimitFilter(boolean enabled,int authBurst,MeterRegistry metrics,int aiBurst,int uploadBurst,int maximumEntries){
        this(enabled,authBurst,metrics,aiBurst,uploadBurst,maximumEntries,"memory",(StringRedisTemplate)null);
    }
    public RequestRateLimitFilter(boolean enabled,int authBurst,MeterRegistry metrics){this(enabled,authBurst,metrics,60,20,20000,"memory",(StringRedisTemplate)null);}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000)
    public void cleanup(){cleanupAt(System.nanoTime());}
    void cleanupAt(long now){
        if("redis".equals(backend))return;
        synchronized(buckets){buckets.entrySet().removeIf(entry->now-entry.getValue().touched>java.time.Duration.ofMinutes(10).toNanos());}
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        if(!enabled || request.getMethod().equals("GET")){chain.doFilter(request,response);return;}
        String path=request.getRequestURI().substring(request.getContextPath().length());
        String group=path.startsWith("/api/auth/")?"auth":path.startsWith("/api/v1/conversation/")||path.endsWith("/coach")||path.endsWith("/coach/events")||path.startsWith("/api/admin/listening")
                || path.startsWith("/api/v1/listening/") && (path.contains("/grading/") && path.endsWith("/retry")
                    || request.getMethod().equals("POST") && path.endsWith("/answers"))?"ai"
                :path.startsWith("/api/admin/") && request.getContentType()!=null && request.getContentType().startsWith("multipart/")?"upload":"";
        if(path.startsWith("/api/billing/orders") || path.startsWith("/api/admin/payments/")) group="billing";
        if(group.isEmpty() && (path.startsWith("/api/v1/practice-sessions")
                || path.startsWith("/api/v1/catalog/") || path.startsWith("/api/v1/listening-learning/")
                || path.startsWith("/api/v1/listening/"))) group="practice";
        if(group.isEmpty()){chain.doFilter(request,response);return;}
        var auth=SecurityContextHolder.getContext().getAuthentication();
        String actor=auth!=null && auth.getPrincipal() instanceof DictaLabPrincipal user?"u:"+user.id():"ip:"+request.getRemoteAddr();
        int capacity=group.equals("auth")?authBurst:group.equals("ai")?aiBurst:group.equals("practice")?600:group.equals("billing")?20:uploadBurst;
        String key=group+":"+actor;
        if("redis".equals(backend)){
            boolean allowed;
            try{
                if(redis==null)throw new org.springframework.dao.DataAccessResourceFailureException("Missing Redis client");
                // The hash tag keeps the script's keys in one Redis Cluster slot.
                Long result=redis.execute(TAKE_REDIS_TOKEN,
                        java.util.List.of("{dictalab-rate}:v1:"+key,"{dictalab-rate}:v1:index"),
                        Integer.toString(capacity),Integer.toString(maximumEntries));
                if(result==null)throw new org.springframework.dao.DataAccessResourceFailureException("Missing Redis result");
                allowed=result==1;
            }catch(org.springframework.dao.DataAccessException unavailable){
                metrics.counter("dictalab.rate_limit.redis_failures").increment();
                reject(response,503,"Dịch vụ tạm thời không khả dụng. Vui lòng thử lại sau.");return;
            }
            if(!allowed){metrics.counter("dictalab.rate_limit.rejected","group",group).increment();
                reject(response,429,"Bạn thao tác quá nhanh. Vui lòng chờ vài giây rồi thử lại.");return;}
            chain.doFilter(request,response);return;
        }
        long now=System.nanoTime();
        Bucket bucket=buckets.get(key);
        if(bucket==null){
            synchronized(buckets){
                bucket=buckets.get(key);
                if(bucket==null && buckets.size()<maximumEntries){bucket=new Bucket(capacity,now);buckets.put(key,bucket);}
            }
        }
        if(bucket==null || !bucket.take(capacity,now)){
            metrics.counter("dictalab.rate_limit.rejected","group",group).increment();
            reject(response,429,"Bạn thao tác quá nhanh. Vui lòng chờ vài giây rồi thử lại.");return;
        }
        chain.doFilter(request,response);
    }
    private void reject(HttpServletResponse response,int status,String message)throws IOException{
        response.setHeader("Retry-After",status==429?"5":"3");
        response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":"+status+",\"message\":\""+message+"\"}");
    }
    static final class Bucket {
        double tokens; volatile long touched;
        Bucket(int capacity,long now){tokens=capacity;touched=now;}
        synchronized boolean take(int capacity,long now){
            tokens=Math.min(capacity,tokens+(now-touched)/1_000_000_000.0*capacity/60.0);touched=now;
            if(tokens<1)return false;tokens--;return true;
        }
    }
}
