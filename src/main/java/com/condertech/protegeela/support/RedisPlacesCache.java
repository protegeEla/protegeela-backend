package com.condertech.protegeela.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class RedisPlacesCache {
    private static final Logger log = LoggerFactory.getLogger(RedisPlacesCache.class);
    private static final TypeReference<Map<String, Object>> TYPE = new TypeReference<>() {};
    private static final DefaultRedisScript<Long> LIMIT = new DefaultRedisScript<>("""
        if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
        local count = tonumber(redis.call('GET', KEYS[2]) or '0')
        if count >= 100 then return 0 end
        redis.call('SET', KEYS[1], '1', 'EX', 20)
        redis.call('INCR', KEYS[2])
        redis.call('EXPIRE', KEYS[2], 172800)
        return 1
        """, Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private volatile long retryAfter;

    public RedisPlacesCache(StringRedisTemplate redis, ObjectMapper json) { this.redis = redis; this.json = json; }

    public Map<String, Object> get(String query) {
        if (System.currentTimeMillis() < retryAfter) return null;
        try {
            String value = redis.opsForValue().get(key(query));
            return value == null ? null : json.readValue(value, TYPE);
        } catch (RuntimeException ex) { unavailable(); return null; }
    }

    public void put(String query, Map<String, Object> value) {
        if (System.currentTimeMillis() < retryAfter) return;
        try { redis.opsForValue().set(key(query), json.writeValueAsString(value), Duration.ofMinutes(15)); }
        catch (RuntimeException ex) { unavailable(); }
    }

    public boolean allowRequest(long day) {
        if (System.currentTimeMillis() < retryAfter) return true; // Existing local limits remain active.
        try {
            return Long.valueOf(1).equals(redis.execute(LIMIT,
                    List.of("protegeela:{places}:cooldown", "protegeela:{places}:daily:" + day)));
        } catch (RuntimeException ex) { unavailable(); return true; }
    }

    private synchronized void unavailable() {
        if (System.currentTimeMillis() >= retryAfter) log.warn("Redis indisponivel para locais; usando cache e limites locais por 30 segundos.");
        retryAfter = System.currentTimeMillis() + 30000;
    }

    private static String key(String query) {
        try {
            return "protegeela:{places}:cache:v1:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(query.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
