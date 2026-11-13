package com.ddareungi.global.http;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Kakao-only HTTP timeouts. Kakao Local/Mobility calls sit on the user's request path, so they get
 * tighter limits than the global spring.http.clients values kept for Seoul API and ORS.
 */
@ConfigurationProperties("kakao.http")
public record KakaoHttpClientProperties(
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout) {

    public KakaoHttpClientProperties {
        requirePositive("kakao.http.connect-timeout", connectTimeout);
        requirePositive("kakao.http.read-timeout", readTimeout);
    }

    /** A new JDK-based factory per caller; each Kakao client owns one HttpClient for its lifetime. */
    public ClientHttpRequestFactory requestFactory() {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                // Same redirect behaviour Spring Boot applies to its default JDK client.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be a positive duration");
        }
    }
}
