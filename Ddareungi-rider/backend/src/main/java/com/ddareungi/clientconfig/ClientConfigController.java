package com.ddareungi.clientconfig;

import com.ddareungi.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/client-config} (spec 6): outside {@code /api/v1}, never blocked by maintenance and not rate
 * limited. {@code Cache-Control: no-store} is set before validation, so 400 responses carry it as well.
 */
@RestController
public class ClientConfigController {
    private final ClientConfigService service;

    public ClientConfigController(ClientConfigService service) {
        this.service = service;
    }

    @GetMapping("/api/client-config")
    public ApiResponse<ClientConfigResponse> clientConfig(
            @RequestParam(required = false) @Nullable String platform,
            @RequestParam(required = false) @Nullable String appVersion,
            // Diagnostic only in v1 (spec 6); accepted and ignored.
            @RequestParam(required = false) @Nullable String buildNumber,
            HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return ApiResponse.ok(service.resolve(platform, appVersion));
    }
}
