package com.relay;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Endpoint(
        UUID id,
        String url,
        String secret,
        List<String> eventTypes,
        boolean active,
        Instant createdAt) {
}
