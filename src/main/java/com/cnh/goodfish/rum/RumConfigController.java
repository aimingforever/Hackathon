package com.cnh.goodfish.rum;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configuration for Dash0 real-user monitoring in the browser.
 *
 * <p>The token is an ingest-only Dash0 token. It is public by nature (every visitor's browser uses
 * it), but it is still injected from the environment instead of being committed to the repository.
 * When no token is configured, the pages simply run without RUM.
 */
@RestController
class RumConfigController {

    record RumConfig(boolean enabled, String endpoint, String authToken, String serviceName, String environment) {
    }

    private final RumConfig config;

    RumConfigController(@Value("${goodfish.rum.endpoint:}") String endpoint,
                        @Value("${goodfish.rum.auth-token:}") String authToken,
                        @Value("${goodfish.rum.service-name:askonce-web}") String serviceName,
                        @Value("${goodfish.rum.environment:hackathon}") String environment) {
        boolean enabled = !endpoint.isBlank() && !authToken.isBlank();
        this.config = enabled
                ? new RumConfig(true, endpoint, authToken, serviceName, environment)
                : new RumConfig(false, null, null, serviceName, environment);
    }

    @GetMapping("/api/rum-config")
    RumConfig config() {
        return config;
    }
}
