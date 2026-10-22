package com.risheesh.offlinepay;

import com.risheesh.offlinepay.security.RedisRequestLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class RedisRateLimitIT {
    @Container static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4.11-alpine")).withExposedPorts(6379);
    @Test void concurrentLuaCounterIsAtomicAndHasExpiry() throws Exception {
        var factory = new LettuceConnectionFactory(redis.getHost(),redis.getMappedPort(6379)); factory.afterPropertiesSet(); factory.start();
        var template = new StringRedisTemplate(factory); var limiter = new RedisRequestLimiter(template);
        var pool=Executors.newFixedThreadPool(8); String key=UUID.randomUUID().toString();
        try {
            List<Callable<Boolean>> tasks=new ArrayList<>(); for(int i=0;i<50;i++)tasks.add(()->limiter.allow(key,10));
            int allowed=0; for(var result:pool.invokeAll(tasks))if(result.get())allowed++;
            assertEquals(10,allowed); assertTrue(template.getExpire("offlinepay:rate:"+key)>0);
        } finally { pool.shutdownNow(); factory.destroy(); }
    }
}
