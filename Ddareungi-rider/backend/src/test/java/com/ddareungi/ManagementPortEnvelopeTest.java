package com.ddareungi;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real embedded servers: the API port answers with the envelope and X-Request-Id, while the separate Actuator port
 * keeps Actuator's own JSON (spec 8.3: Actuator responses are not wrapped).
 */
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class ManagementPortEnvelopeTest {
    @LocalServerPort int apiPort;
    @LocalManagementPort int managementPort;
    final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    private HttpResponse<String> get(int port, String path, String requestId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (requestId != null) request.header("X-Request-Id", requestId);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void apiPortUsesEnvelopeAndManagementPortDoesNot() throws Exception {
        assertThat(managementPort).isNotEqualTo(apiPort);

        var api = get(apiPort, "/api/v1/status", "real-server-1");
        assertThat(api.statusCode()).isEqualTo(200);
        assertThat(api.headers().firstValue("X-Request-Id")).hasValue("real-server-1");
        assertThat(api.body()).contains("\"success\":true", "\"requestId\":\"real-server-1\"");

        var notFound = get(apiPort, "/api/v1/nope", null);
        assertThat(notFound.statusCode()).isEqualTo(404);
        assertThat(notFound.body()).contains("\"code\":\"RESOURCE_NOT_FOUND\"");
        assertThat(notFound.body()).contains("\"requestId\":\"" + notFound.headers().firstValue("X-Request-Id").orElseThrow() + "\"");

        var health = get(managementPort, "/actuator/health", null);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"").doesNotContain("\"success\"", "\"meta\"");
        var liveness = get(managementPort, "/actuator/health/liveness", null);
        assertThat(liveness.statusCode()).isEqualTo(200);
        assertThat(liveness.body()).doesNotContain("\"success\"");
    }
}
