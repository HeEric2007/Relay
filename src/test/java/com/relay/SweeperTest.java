package com.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class SweeperTest extends IntegrationTest {

    @Test
    void reclaimsADeliveryLeftLockedByACrashedWorker() {
        UUID deliveryId = insertDeliveryStuckInDelivering();

        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    Delivery reclaimed = fetch(deliveryId);
                    assertThat(reclaimed.attempts()).isGreaterThan(0);
                    assertThat(reclaimed.status()).isNotEqualTo("delivering");
                });
    }

    /** Port 1 refuses instantly, so the reclaimed delivery fails without waiting on DNS. */
    private UUID insertDeliveryStuckInDelivering() {
        UUID endpointId = db.sql("""
                insert into endpoints (url, secret)
                values ('http://127.0.0.1:1/hook', 'whsec_test')
                returning id
                """)
                .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                .single();

        UUID eventId = db.sql("""
                insert into events (type, payload)
                values ('invoice.paid', cast('{}' as jsonb))
                returning id
                """)
                .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                .single();

        return db.sql("""
                insert into deliveries (event_id, endpoint_id, status, locked_at, locked_by)
                values (:eventId, :endpointId, 'delivering', now() - interval '1 hour', 'worker-that-died')
                returning id
                """)
                .param("eventId", eventId)
                .param("endpointId", endpointId)
                .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                .single();
    }

    private Delivery fetch(UUID deliveryId) {
        return http.getForObject("/deliveries/" + deliveryId, Delivery.class);
    }
}
