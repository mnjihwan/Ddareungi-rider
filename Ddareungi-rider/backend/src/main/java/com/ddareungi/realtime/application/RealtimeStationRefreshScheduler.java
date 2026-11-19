package com.ddareungi.realtime.application;

import com.ddareungi.realtime.infrastructure.RedisRealtimeStationStore;
import com.ddareungi.realtime.infrastructure.SeoulBikeRealtimeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Profile("infra")
@Component
@ConditionalOnProperty(name = "seoul-bike.api.collection-enabled", havingValue = "true", matchIfMissing = true)
public class RealtimeStationRefreshScheduler {
    private static final Logger log = LoggerFactory.getLogger(RealtimeStationRefreshScheduler.class);
    private final SeoulBikeRealtimeClient client;
    private final RedisRealtimeStationStore store;

    public RealtimeStationRefreshScheduler(SeoulBikeRealtimeClient client, RedisRealtimeStationStore store) {
        this.client = client;
        this.store = store;
    }

    @Scheduled(cron = "${seoul-bike.api.refresh-cron:0 * * * * *}", zone = "Asia/Seoul")
    @EventListener(ApplicationReadyEvent.class)
    public void refresh() {
        long started = System.nanoTime();
        try {
            store.markAttempt(Instant.now());
        } catch (RuntimeException exception) {
            log.warn("실시간 수집 시도 시각 기록 실패 ({})", exception.getClass().getSimpleName());
        }
        try {
            var statuses = client.fetchAll();
            store.saveAll(statuses);
            store.markSuccess(Instant.now());
            log.info("OA-15493 실시간 대여정보 {}건 Redis 갱신 완료 ({}ms)", statuses.size(),
                    (System.nanoTime() - started) / 1_000_000);
        } catch (Exception exception) {
            log.error("실시간 수집 실패 ({}). API 키와 Redis 연결을 확인하세요.", exception.getClass().getSimpleName());
        }
    }
}
