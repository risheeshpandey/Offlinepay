package com.risheesh.offlinepay.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(name = "offlinepay.rate-limit.enabled", havingValue = "true")
public class RedisRequestLimiter {
    private final StringRedisTemplate redis;
    // INCR + expiry are atomic, including the first request. Expiry cannot be lost between commands.
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
        local count = redis.call('INCR', KEYS[1])
        if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
        return count
        """, Long.class);
    public boolean allow(String key, int limit) {
        Long count = redis.execute(SCRIPT, List.of("offlinepay:rate:" + key), "60000");
        if (count == null) throw new IllegalStateException("Rate limiter unavailable");
        return count <= limit;
    }
}
