package com.ddareungi.global.http;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Per-provider HTTP limits (D8). Place and route calls sit on the user's request path, so each gets its own
 * connect/read timeout and a total budget for the whole user request; ORS has its own values instead of the global
 * {@code spring.http.clients} defaults kept for the Seoul API.
 *
 * <ul>
 *   <li>{@code provider.http.place}: Kakao Local, connect 1s / read 2.5s / whole place request 3s</li>
 *   <li>{@code provider.http.route}: Kakao Mobility, connect 1s / read 4s / whole route plan 10s</li>
 *   <li>{@code provider.http.ors}: openrouteservice, connect 1s / read 4s (the plan budget still applies)</li>
 * </ul>
 */
@ConfigurationProperties("provider.http")
public record ProviderHttpProperties(@DefaultValue Place place, @DefaultValue Route route, @DefaultValue Ors ors) {

    /** All defaults, for code paths created without Spring (tests, manual wiring). */
    public static ProviderHttpProperties defaults() {
        return new Binder().bindOrCreate("provider.http", ProviderHttpProperties.class);
    }

    /** Connect/read timeouts of one provider and the JDK client factory built from them. */
    public interface Timeouts {
        Duration connectTimeout();
        Duration readTimeout();

        /** A new JDK-based factory per caller; each client owns one HttpClient for its lifetime. */
        default ClientHttpRequestFactory requestFactory() {
            var httpClient = HttpClient.newBuilder()
                    .connectTimeout(connectTimeout())
                    // Same redirect behaviour Spring Boot applies to its default JDK client.
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            var factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(readTimeout());
            return factory;
        }
    }

    public record Place(@DefaultValue("1s") Duration connectTimeout, @DefaultValue("2500ms") Duration readTimeout,
                        @DefaultValue("3s") Duration totalTimeout) implements Timeouts {
        public Place {
            requirePositive("provider.http.place.connect-timeout", connectTimeout);
            requirePositive("provider.http.place.read-timeout", readTimeout);
            requirePositive("provider.http.place.total-timeout", totalTimeout);
        }
    }

    public record Route(@DefaultValue("1s") Duration connectTimeout, @DefaultValue("4s") Duration readTimeout,
                        @DefaultValue("10s") Duration totalTimeout) implements Timeouts {
        public Route {
            requirePositive("provider.http.route.connect-timeout", connectTimeout);
            requirePositive("provider.http.route.read-timeout", readTimeout);
            requirePositive("provider.http.route.total-timeout", totalTimeout);
        }
    }

    public record Ors(@DefaultValue("1s") Duration connectTimeout, @DefaultValue("4s") Duration readTimeout)
            implements Timeouts {
        public Ors {
            requirePositive("provider.http.ors.connect-timeout", connectTimeout);
            requirePositive("provider.http.ors.read-timeout", readTimeout);
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be a positive duration");
        }
    }
}
