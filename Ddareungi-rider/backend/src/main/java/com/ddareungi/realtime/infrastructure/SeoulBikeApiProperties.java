package com.ddareungi.realtime.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("seoul-bike.api")
public record SeoulBikeApiProperties(
        String baseUrl,
        String key,
        int pageSize,
        int maxPages,
        String realtimeService,
        String refreshCron
) {}
