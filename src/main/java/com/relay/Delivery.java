package com.relay;

import java.time.Instant;
import java.util.UUID;

public record Delivery(
        UUID id,
        UUID eventId,
        UUID endpointId,
        String status,
        int attempts,
        Instant runAt,
        Instant lockedAt,
        String lockedBy,
        Integer lastStatusCode,
        String lastError,
        Instant createdAt) {
}
