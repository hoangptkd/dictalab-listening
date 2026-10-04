package com.dictalab.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Only hashes of refresh secrets are retained. All scripts use one Redis Cluster slot. */
@Component
public class RedisTokenStore {
    private static final String PREFIX = "{dictalab-auth}:v1:";
    private static final DefaultRedisScript<Long> CREATE = new DefaultRedisScript<>("""
            local clock = redis.call('TIME')
            local now = tonumber(clock[1])
            local expires = tonumber(ARGV[4])
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
            redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now)
            -- Discard sessions invalidated by PostgreSQL authVersion before checking per-user capacity.
            for _, session in ipairs(redis.call('ZRANGE', KEYS[3], 0, -1)) do
                if redis.call('HGET', session, 'version') ~= ARGV[2] then
                    redis.call('DEL', session)
                    redis.call('ZREM', KEYS[2], session)
                    redis.call('ZREM', KEYS[3], session)
                end
            end
            if expires <= now or redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[5]) or
                redis.call('ZCARD', KEYS[3]) >= tonumber(ARGV[6]) then return 0 end
            redis.call('HSET', KEYS[1], 'user', ARGV[1], 'version', ARGV[2],
                'current', ARGV[3], 'expires', ARGV[4], 'rotations', '0')
            redis.call('EXPIREAT', KEYS[1], expires)
            redis.call('ZADD', KEYS[2], expires, KEYS[1])
            redis.call('EXPIREAT', KEYS[2], math.max(expires, now + redis.call('TTL', KEYS[2])))
            redis.call('ZADD', KEYS[3], expires, KEYS[1])
            redis.call('EXPIREAT', KEYS[3], math.max(expires, now + redis.call('TTL', KEYS[3])))
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> ROTATE = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            if redis.call('HGET', KEYS[1], 'user') ~= ARGV[3] or
                redis.call('HGET', KEYS[1], 'version') ~= ARGV[4] then return 0 end
            if redis.call('HGET', KEYS[1], 'current') ~= ARGV[1] then
                if redis.call('HEXISTS', KEYS[1], 'used:' .. ARGV[1]) == 1 then
                    redis.call('DEL', KEYS[1])
                    redis.call('ZREM', KEYS[2], KEYS[1])
                    redis.call('ZREM', KEYS[3], KEYS[1])
                    return -1
                end
                return 0
            end
            if tonumber(redis.call('HGET', KEYS[1], 'rotations')) >= 2048 then return -2 end
            redis.call('HSET', KEYS[1], 'used:' .. ARGV[1], '1', 'current', ARGV[2])
            redis.call('HINCRBY', KEYS[1], 'rotations', 1)
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> REVOKE = new DefaultRedisScript<>("""
            local user = redis.call('HGET', KEYS[1], 'user')
            redis.call('DEL', KEYS[1])
            redis.call('ZREM', KEYS[2], KEYS[1])
            if user then redis.call('ZREM', ARGV[1] .. user, KEYS[1]) end
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final int maximumSessions;
    private final int sessionsPerUser;

    public RedisTokenStore(StringRedisTemplate redis,
            @Value("${dictalab.auth.maximum-sessions:20000}") int maximumSessions,
            @Value("${dictalab.auth.sessions-per-user:10}") int sessionsPerUser) {
        if (maximumSessions < 1 || sessionsPerUser < 1) throw new IllegalArgumentException("Invalid session limits");
        this.redis = redis;
        this.maximumSessions = maximumSessions;
        this.sessionsPerUser = sessionsPerUser;
    }

    public void create(String sid, long user, long version, String hash, Instant expires) {
        Long result = call(() -> redis.execute(CREATE, keys(sid, user), Long.toString(user), Long.toString(version),
                hash, Long.toString(expires.getEpochSecond()), Integer.toString(maximumSessions), Integer.toString(sessionsPerUser)));
        if (!Long.valueOf(1).equals(result))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Đã đạt giới hạn phiên đăng nhập. Vui lòng đăng xuất các phiên cũ.");
    }

    public Map<Object, Object> session(String sid) {
        // A long-lived session can have many used-token hashes; never download them on every request.
        var fields = List.<Object>of("user", "version", "current", "expires");
        var values = call(() -> redis.opsForHash().multiGet(key(sid), fields));
        Map<Object, Object> metadata = new java.util.HashMap<>();
        for (int i = 0; i < fields.size(); i++) if (values.get(i) != null) metadata.put(fields.get(i), values.get(i));
        return metadata;
    }

    public boolean recognizes(String sid, String hash) {
        var values = call(() -> redis.opsForHash().multiGet(key(sid), List.of("current", "used:" + hash)));
        return hash.equals(values.get(0)) || values.get(1) != null;
    }

    public boolean active(String sid, long user, long version) {
        var saved = session(sid);
        return Long.toString(user).equals(saved.get("user")) && Long.toString(version).equals(saved.get("version"));
    }

    public void rotate(String sid, long user, long version, String oldHash, String newHash) {
        Long result = call(() -> redis.execute(ROTATE, keys(sid, user), oldHash, newHash, Long.toString(user), Long.toString(version)));
        if (!Long.valueOf(1).equals(result))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập đã hết hạn hoặc không hợp lệ.");
    }

    public void revoke(String sid) {
        call(() -> redis.execute(REVOKE, List.of(key(sid), PREFIX + "index"), PREFIX + "user:"));
    }

    private <T> T call(java.util.function.Supplier<T> operation) {
        try {
            T result = operation.get();
            if (result == null) throw new Unavailable();
            return result;
        } catch (org.springframework.dao.DataAccessException failure) { throw new Unavailable(); }
    }

    /** Deliberately excludes Redis URLs, token values and underlying connection messages. */
    public static class Unavailable extends RuntimeException {
        public Unavailable() { super("Authentication store unavailable"); }
    }

    private List<String> keys(String sid, long user) {
        return List.of(key(sid), PREFIX + "index", PREFIX + "user:" + user);
    }
    private String key(String sid) { return PREFIX + "session:" + sid; }
}
