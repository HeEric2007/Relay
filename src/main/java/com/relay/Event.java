package com.relay;

import java.time.Instant;
import java.util.UUID;

public record Event(
        UUID id,
        String type,
        String payload,
        Instant createdAt) {
}
