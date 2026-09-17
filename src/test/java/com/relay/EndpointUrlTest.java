package com.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * The base class allows loopback so the WireMock receivers work. This class turns the
 * guard back on, which is how it runs in production.
 */
@TestPropertySource(properties = "relay.endpoints.allow-private-hosts=false")
class EndpointUrlTest extends IntegrationTest {

    @Test
    void refusesLoopback() {
        assertThat(register("http://127.0.0.1:9999/hook")).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void refusesTheCloudMetadataAddress() {
        assertThat(register("http://169.254.169.254/latest/meta-data/")).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void refusesAPrivateNetwork() {
        assertThat(register("http://10.0.0.5/hook")).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void acceptsAPublicAddress() {
        assertThat(register("http://8.8.8.8/hook")).isEqualTo(HttpStatus.CREATED);
    }

    private HttpStatusCode register(String url) {
        ResponseEntity<String> response = http.postForEntity(
                "/endpoints", Map.of("url", url, "eventTypes", List.of()), String.class);
        return response.getStatusCode();
    }
}
