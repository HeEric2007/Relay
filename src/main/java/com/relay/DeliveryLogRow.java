package com.relay;

import java.time.Instant;
import java.util.UUID;

/**
 * A delivery as the log views show it: the event's type joined in, and the
 * worker's locking columns left out. {@link Delivery} stays the raw table row.
 */
public record DeliveryLogRow(
        UUID id,
        UUID eventId,
        String eventType,
        UUID endpointId,
        String status,
        int attempts,
        Instant runAt,
        Integer lastStatusCode,
        String lastError,
        Instant createdAt) {
}
