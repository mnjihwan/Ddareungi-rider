package com.ddareungi.place.infrastructure;

import com.ddareungi.place.Place;
import com.ddareungi.place.application.PlaceSearchCache;
import com.ddareungi.station.domain.Location;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisPlaceSearchCacheTest {
    static final String KEY = "place:v1:abc";
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
    MutableClock clock = new MutableClock();
    PlaceCacheProperties properties = new PlaceCacheProperties(true, Duration.ofMinutes(5), Duration.ofSeconds(30));
    RedisPlaceSearchCache cache;

    @BeforeEach void setup() {
        when(redis.opsForValue()).thenReturn(values);
        cache = new RedisPlaceSearchCache(redis, JsonMapper.builder().build(), properties, clock);
    }

    @Test void storesWithFiveMinuteTtlAndReadsBackTheSamePlaces() {
        var places = List.of(new Place("강남역 2호선", "서울 강남대로", new Location(37.4979, 127.0276),
                "https://place.map.kakao.com/1", "SW8"), new Place("주소", "주소", new Location(37.5, 127.0), null, ""));
        var stored = new AtomicReference<String>();
        doAnswer(invocation -> { stored.set(invocation.getArgument(1)); return null; })
                .when(values).set(eq(KEY), anyString(), eq(Duration.ofMinutes(5)));
        cache.put(KEY, places);
        when(values.get(KEY)).thenReturn(stored.get());
        assertThat(cache.get(KEY)).isEqualTo(places);
    }

    @Test void missAndCorruptEntriesAreNull() {
        assertThat(cache.get(KEY)).isNull();
        when(values.get(KEY)).thenReturn("not json");
        assertThat(cache.get(KEY)).isNull();
    }

    @Test void redisErrorFailsOpenAndBypassesRedisForTheBackoff() {
        when(values.get(KEY)).thenThrow(new RedisConnectionFailureException("down"));
        assertThat(cache.get(KEY)).isNull();
        clock.advance(Duration.ofSeconds(29));
        assertThat(cache.get(KEY)).isNull();
        cache.put(KEY, List.of());
        verify(values, times(1)).get(KEY);
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));

        clock.advance(Duration.ofSeconds(2));
        reset(values);
        when(values.get(KEY)).thenReturn("[]");
        assertThat(cache.get(KEY)).isEmpty();
    }

    @Test void writeErrorIsSwallowed() {
        doThrow(new RedisConnectionFailureException("down")).when(values).set(anyString(), anyString(), any(Duration.class));
        cache.put(KEY, List.of());
        assertThat(cache.get(KEY)).isNull();
        verify(values, never()).get(anyString());
    }

    @Test void onlyTheInfraProfileCreatesTheCacheAndItCanBeDisabled() {
        var runner = new ApplicationContextRunner()
                .withBean(StringRedisTemplate.class, () -> redis)
                .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
                .withUserConfiguration(PropertiesConfig.class, RedisPlaceSearchCache.class);
        runner.run(context -> assertThat(context).doesNotHaveBean(PlaceSearchCache.class));
        runner.withPropertyValues("spring.profiles.active=local")
                .run(context -> assertThat(context).doesNotHaveBean(PlaceSearchCache.class));
        runner.withPropertyValues("spring.profiles.active=infra")
                .run(context -> assertThat(context).hasSingleBean(PlaceSearchCache.class));
        runner.withPropertyValues("spring.profiles.active=infra", "place.cache.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PlaceSearchCache.class));
    }

    @EnableConfigurationProperties(PlaceCacheProperties.class)
    static class PropertiesConfig {}

    static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-16T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
