package com.tailor.web.api;

import io.swagger.v3.oas.annotations.Operation;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "app.role", havingValue = "api")
public class HealthController {

    private final String role;

    public HealthController(@Value("${app.role}") String role) {
        this.role = role;
    }

    @Operation(summary = "Liveness check for the load balancer", operationId = "health")
    @GetMapping("/healthz")
    public Map<String, String> health() {
        return Map.of("status", "ok", "role", role);
    }
}
