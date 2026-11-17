package com.ddareungi.global.metrics;

import com.ddareungi.global.ratelimit.RateLimitInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 3 O8 on real embedded servers: Prometheus scrape on the management port only, the custom meters, the access
 * log line and the privacy rule that request coordinates never reach the log (spec 26).
 */
@ActiveProfiles("local")
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "kakao.maps.rest-api-key=",
        "ratelimit.places.burst=1",
        "ratelimit.places.per-minute=1"
})
class ObservabilityIntegrationTest {
    // Distinctive coordinate strings so a hit in the log can only come from this request.
    static final String LATITUDE = "37.4979123";
    static final String LONGITUDE = "127.0276456";

    @LocalServerPort int apiPort;
    @LocalManagementPort int managementPort;
    @Autowired MeterRegistry registry;
    @Autowired Environment environment;
    final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    private HttpResponse<String> get(int port, String pathAndQuery) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pathAndQuery)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test void prometheusIsServedOnTheManagementPortWithTheCustomMeters() throws Exception {
        get(apiPort, "/api/v1/status"); // at least one http.server.requests sample

        var scrape = get(managementPort, "/actuator/prometheus");
        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("text/plain"));
        String body = scrape.body();
        assertThat(body).contains(
                "ddareungi_provider_calls_seconds_count{application=\"ddareungrider\",outcome=\"success\",provider=\"place\"}",
                "ddareungi_provider_calls_seconds_bucket{application=\"ddareungrider\",outcome=\"timeout\",provider=\"bicycle\",le=",
                "ddareungi_provider_circuit_state{application=\"ddareungrider\",provider=\"walking\",state=\"closed\"} 1.0",
                "ddareungi_provider_circuit_state{application=\"ddareungrider\",provider=\"walking\",state=\"open\"} 0.0",
                "ddareungi_realtime_snapshot_age_seconds{application=\"ddareungrider\"}",
                "ddareungi_realtime_stations{application=\"ddareungrider\",status=\"fresh\"}",
                "ddareungi_realtime_stations{application=\"ddareungrider\",status=\"unavailable\"}",
                "ddareungi_ratelimit_rejected_total{application=\"ddareungrider\",group=\"routes\"}",
                "http_server_requests_seconds_bucket{application=\"ddareungrider\",",
                "jvm_memory_used_bytes{application=\"ddareungrider\",area=\"heap\"");
        // Tags never carry coordinates or station ids.
        assertThat(body).doesNotContain("latitude", "longitude", "stationId");
    }

    @Test void actuatorStaysOffTheApiPort() throws Exception {
        assertThat(get(apiPort, "/actuator/prometheus").statusCode()).isEqualTo(404);
        assertThat(get(apiPort, "/actuator/health").statusCode()).isEqualTo(404);
        assertThat(get(apiPort, "/actuator").statusCode()).isEqualTo(404);
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    @Test void accessLogHasTheFieldsButNoCoordinates(CapturedOutput output) throws Exception {
        var response = get(apiPort, "/api/v1/stations/nearby?latitude=" + LATITUDE + "&longitude=" + LONGITUDE + "&radius=500");
        assertThat(response.statusCode()).isEqualTo(200);
        String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();

        // The line is written after the response is committed; wait for it rather than racing the server thread.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getOut())
                .contains("method=GET path=/api/v1/stations/nearby status=200 elapsedMs="));
        String line = output.getOut().lines().filter(text -> text.contains("path=/api/v1/stations/nearby"))
                .findFirst().orElseThrow();
        // Text ("[id]") or ECS JSON ("requestId":"id"): the logging system is JVM-wide, so an infra-profile context
        // started earlier in the same test JVM can leave the console in ECS format.
        assertThat(line).contains(requestId).containsPattern("elapsedMs=\\d+");
        assertThat(output.getAll()).doesNotContain(LATITUDE, LONGITUDE, "latitude=", "longitude=", "4979123", "0276456");

        get(managementPort, "/actuator/health");
        assertThat(output.getAll()).doesNotContain("path=/actuator");
    }

    @Test void localProfileKeepsTextLogs() {
        assertThat(environment.getProperty("logging.structured.format.console")).isNull();
    }

    @Test void rateLimitRejectionsAreCountedPerGroup() throws Exception {
        var counter = registry.find(RateLimitInterceptor.REJECTED_METER).tag("group", "places").counter();
        assertThat(counter).isNotNull();
        double before = counter.count();
        int rejected = 0;
        for (int i = 0; i < 3; i++) {
            if (get(apiPort, "/api/v1/places/search?query=x").statusCode() == 429) rejected++;
        }
        assertThat(rejected).isEqualTo(2); // burst 1
        assertThat(counter.count() - before).isEqualTo(2.0);
        assertThat(registry.find(RateLimitInterceptor.REJECTED_METER).tag("group", "stations").counter().count()).isZero();
        assertThat(get(managementPort, "/actuator/prometheus").body())
                .containsPattern("ddareungi_ratelimit_rejected_total\\{application=\"ddareungrider\",group=\"places\"} [1-9]");
    }
}
