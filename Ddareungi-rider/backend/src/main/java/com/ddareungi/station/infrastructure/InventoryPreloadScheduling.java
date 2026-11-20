package com.ddareungi.station.infrastructure;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@Profile("infra")
public class InventoryPreloadScheduling {
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler inventoryPreloadScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("inventory-preload-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
