package com.cnh.goodfish.rum;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RumConfigControllerTests {

    @Test
    void disabledWithoutToken() {
        var config = new RumConfigController("https://ingress.example.com", "", "askonce-web", "test").config();

        assertThat(config.enabled()).isFalse();
        assertThat(config.authToken()).isNull();
        assertThat(config.endpoint()).isNull();
    }

    @Test
    void enabledWithEndpointAndToken() {
        var config = new RumConfigController("https://ingress.example.com", "ingest-token", "askonce-web", "test").config();

        assertThat(config.enabled()).isTrue();
        assertThat(config.endpoint()).isEqualTo("https://ingress.example.com");
        assertThat(config.serviceName()).isEqualTo("askonce-web");
    }
}
