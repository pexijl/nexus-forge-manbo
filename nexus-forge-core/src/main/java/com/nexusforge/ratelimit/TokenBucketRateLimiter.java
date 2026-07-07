package com.nexusforge.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class TokenBucketRateLimiter implements RateLimiter {

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    @Override
    public boolean tryAcquire(String key, RateLimit anno) {
        Bucket bucket = buckets.get(key, k -> buildBucket(anno));
        return bucket.tryConsume(1);
    }

    private static Bucket buildBucket(RateLimit anno) {
        int capacity = anno.capacity() > 0 ? anno.capacity() : anno.qps();
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(anno.qps(), Duration.ofSeconds(1))
                        .build())
                .build();
    }
}