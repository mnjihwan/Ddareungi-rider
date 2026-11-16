package com.ddareungi.place.infrastructure;

import com.ddareungi.place.Place;
import com.ddareungi.place.application.PlaceSearchCache;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.util.List;

/**
 * Redis-backed {@link PlaceSearchCache}. Fails open: every Redis or decoding error is a miss, and after a Redis
 * error the cache is bypassed for {@code place.cache.failure-backoff} so searches do not wait on a dead Redis.
 */
@Component
@Profile("infra")
@ConditionalOnProperty(name = "place.cache.enabled", havingValue = "true", matchIfMissing = true)
public class RedisPlaceSearchCache implements PlaceSearchCache {
    private static final Logger log = LoggerFactory.getLogger(RedisPlaceSearchCache.class);
    private final StringRedisTemplate redis;
    private final JsonMapper mapper;
    private final PlaceCacheProperties properties;
    private final Clock clock;
    private volatile long bypassUntilMillis;

    @Autowired
    public RedisPlaceSearchCache(StringRedisTemplate redis, JsonMapper mapper, PlaceCacheProperties properties) {
        this(redis, mapper, properties, Clock.systemUTC());
    }

    RedisPlaceSearchCache(StringRedisTemplate redis, JsonMapper mapper, PlaceCacheProperties properties, Clock clock) {
        this.redis = redis;
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public @Nullable List<Place> get(String key) {
        if (bypassed()) return null;
        String value;
        try {
            value = redis.opsForValue().get(key);
        } catch (RuntimeException error) {
            redisFailed(error);
            return null;
        }
        if (value == null) return null;
        try {
            return List.of(mapper.readValue(value, Place[].class));
        } catch (RuntimeException error) {
            // A corrupt or old-format entry is a miss; the next successful search overwrites it.
            log.debug("Ignoring unreadable place cache entry", error);
            return null;
        }
    }

    @Override
    public void put(String key, List<Place> places) {
        if (bypassed()) return;
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(places.toArray(Place[]::new)), properties.ttl());
        } catch (RuntimeException error) {
            redisFailed(error);
        }
    }

    private boolean bypassed() { return clock.millis() < bypassUntilMillis; }

    private void redisFailed(RuntimeException error) {
        bypassUntilMillis = clock.millis() + properties.failureBackoff().toMillis();
        log.warn("Place cache unavailable, bypassing it for {} ({})", properties.failureBackoff(), error.getClass().getSimpleName());
    }
}
