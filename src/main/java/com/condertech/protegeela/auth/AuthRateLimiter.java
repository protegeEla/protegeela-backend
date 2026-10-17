package com.condertech.protegeela.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Short-lived counters. Raw addresses, passwords and emails are never logged or stored here. */
@Component
public class AuthRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(AuthRateLimiter.class);
    private static final long WINDOW = 900000;
    private static final DefaultRedisScript<Long> COUNT = new DefaultRedisScript<>("""
        local value = redis.call('INCR', KEYS[1])
        if value == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
        return value
        """, Long.class);
    private record Bucket(long expires, int count) {}
    private final Map<String, Bucket> local = new HashMap<>();
    private final StringRedisTemplate redis;
    private long retryRedisAfter;

    public AuthRateLimiter(StringRedisTemplate redis) { this.redis = redis; }

    public void login(String remoteAddress, String email) {
        take("login-ip:" + remoteAddress, 60);
        take("login-account:" + email.strip().toLowerCase(Locale.ROOT), 10);
    }

    public void register(String remoteAddress) { take("register-ip:" + remoteAddress, 10); }

    public void verifyEmail(String remoteAddress, String email) {
        take("verify-email-ip:" + remoteAddress, 20);
        take("verify-email-account:" + email.strip().toLowerCase(Locale.ROOT), 10);
    }

    public void resendVerification(String remoteAddress, String email) {
        take("resend-email-ip:" + remoteAddress, 10);
        take("resend-email-account:" + email.strip().toLowerCase(Locale.ROOT), 3);
    }

    private synchronized void take(String identity, int max) {
        String key = "protegeela:auth:limit:" + hash(identity);
        long now = System.currentTimeMillis();
        local.entrySet().removeIf(entry -> entry.getValue().expires() <= now);
        // Mirror counters locally so an outage doesn't reset protection on this instance.
        Bucket previous = local.get(key);
        if (previous == null && local.size() >= 10000) throw new AuthRateLimitException();
        Bucket current = new Bucket(previous == null ? now + WINDOW : previous.expires(),
                previous == null ? 1 : Math.min(previous.count() + 1, max + 1));
        local.put(key, current);
        if (current.count() > max) throw new AuthRateLimitException();
        if (now < retryRedisAfter) return;
        try {
            Long count = redis.execute(COUNT, List.of(key), Long.toString(WINDOW));
            if (count != null && count > max) throw new AuthRateLimitException();
        } catch (org.springframework.dao.DataAccessException ex) {
            retryRedisAfter = now + 30000;
            log.warn("Redis indisponivel; protecao de autenticacao operando com limites locais.");
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
