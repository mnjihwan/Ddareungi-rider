package com.ddareungi.realtime.application;

import com.ddareungi.realtime.infrastructure.RedisRealtimeStationStore;
import com.ddareungi.realtime.infrastructure.SeoulBikeRealtimeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

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
        try {
            var statuses = client.fetchAll();
            statuses.forEach(store::save);
            log.info("OA-15493 실시간 대여정보 {}건 Redis 갱신 완료", statuses.size());
        } catch (Exception exception) {
            log.error("실시간 수집 실패 ({}). API 키와 Redis 연결을 확인하세요.", exception.getClass().getSimpleName());
        }
    }
}
