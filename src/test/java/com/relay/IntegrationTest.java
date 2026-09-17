package com.relay;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // WireMock receivers live on loopback, which the SSRF check refuses by default.
        "relay.endpoints.allow-private-hosts=true",
        "relay.worker.poll-interval-ms=50",
        "relay.worker.sweep-interval-ms=50",
        "relay.worker.stuck-after-ms=300",
        // Backoff stays 2^attempts, just measured in 20ms units so five attempts fit in a test.
        "relay.retry.unit-ms=20"
})
abstract class IntegrationTest {

    // Started once for the whole suite: booting Postgres per class is the slowest thing here.
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void useContainerDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcClient db;

    @BeforeEach
    void emptyTheDatabase() {
        db.sql("truncate deliveries, events, endpoints").update();
    }
}
