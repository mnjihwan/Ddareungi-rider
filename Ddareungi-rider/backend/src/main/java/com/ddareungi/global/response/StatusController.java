package com.ddareungi.global.response;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class StatusController {
    private final Environment environment;
    public StatusController(Environment environment) { this.environment = environment; }

    @GetMapping("/api/v1/status")
    public ApiResponse<Map<String, String>> status() {
        return ApiResponse.ok(Map.of("mode", environment.acceptsProfiles(Profiles.of("infra")) ? "infra" : "sample"));
    }
}
