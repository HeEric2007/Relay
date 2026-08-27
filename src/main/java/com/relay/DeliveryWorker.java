package com.relay;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "relay.worker.enabled", havingValue = "true")
public class DeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);
    private static final int MAX_ERROR_LENGTH = 500;

    private final DeliveryRepository deliveries;
    private final EndpointRepository endpoints;
    private final EventRepository events;
    private final HttpClient http;

    private final String workerId;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration retryUnit;
    private final Duration stuckAfter;
    private final Duration requestTimeout;

    DeliveryWorker(DeliveryRepository deliveries,
                   EndpointRepository endpoints,
                   EventRepository events,
                   @Value("${relay.worker.batch-size}") int batchSize,
                   @Value("${relay.retry.max-attempts}") int maxAttempts,
                   @Value("${relay.retry.unit-ms}") long retryUnitMs,
                   @Value("${relay.worker.stuck-after-ms}") long stuckAfterMs,
                   @Value("${relay.delivery.timeout-ms}") long requestTimeoutMs) {
        this.deliveries = deliveries;
        this.endpoints = endpoints;
        this.events = events;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.retryUnit = Duration.ofMillis(retryUnitMs);
        this.stuckAfter = Duration.ofMillis(stuckAfterMs);
        this.requestTimeout = Duration.ofMillis(requestTimeoutMs);
        this.workerId = System.getenv().getOrDefault("HOSTNAME", "local")
                + "-" + ProcessHandle.current().pid();
        this.http = HttpClient.newBuilder()
                .connectTimeout(this.requestTimeout)
                // A redirect would let a registered URL send us somewhere it never declared.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        log.info("delivery worker {} started", this.workerId);
    }

    @Scheduled(fixedDelayString = "${relay.worker.poll-interval-ms}")
    public void deliverPendingBatch() {
        List<Delivery> claimed = deliveries.claim(workerId, batchSize);
        if (claimed.isEmpty()) {
            return;
        }

        // close() waits for every attempt to record its result, so SIGTERM finishes the batch.
        try (ExecutorService attempts = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Delivery delivery : claimed) {
                attempts.submit(() -> attemptDelivery(delivery));
            }
        }
    }

    @Scheduled(fixedDelayString = "${relay.worker.sweep-interval-ms}")
    public void reclaimStuckDeliveries() {
        int reclaimed = deliveries.reclaimStuck(Instant.now().minus(stuckAfter));
        if (reclaimed > 0) {
            log.warn("reclaimed {} deliveries left locked by a dead worker", reclaimed);
        }
    }

    void attemptDelivery(Delivery delivery) {
        Endpoint endpoint = endpoints.findById(delivery.endpointId()).orElseThrow();
        Event event = events.findById(delivery.eventId()).orElseThrow();
        int attempts = delivery.attempts() + 1;

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.url()))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Relay-Event-Id", event.id().toString())
                .header("Relay-Event-Type", event.type())
                .header("Relay-Delivery-Id", delivery.id().toString())
                .header("Relay-Attempt", String.valueOf(attempts))
                .header("Relay-Signature", Signing.sign(endpoint.secret(), event.payload()))
                .POST(HttpRequest.BodyPublishers.ofString(event.payload()))
                .build();

        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                deliveries.markDelivered(delivery.id(), attempts, response.statusCode());
            } else {
                recordFailure(delivery, attempts, response.statusCode(), "HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure(delivery, attempts, null, "interrupted before a response arrived");
        } catch (IOException | RuntimeException e) {
            recordFailure(delivery, attempts, null, describe(e));
        }
    }

    private void recordFailure(Delivery delivery, int attempts, Integer statusCode, String error) {
        if (attempts >= maxAttempts) {
            deliveries.markDead(delivery.id(), attempts, statusCode, error);
            log.warn("delivery {} dead after {} attempts: {}", delivery.id(), attempts, error);
            return;
        }
        Instant runAt = Instant.now().plus(backoffAfter(attempts));
        deliveries.markRetry(delivery.id(), attempts, runAt, statusCode, error);
    }

    private Duration backoffAfter(int attempts) {
        return retryUnit.multipliedBy(1L << attempts);
    }

    private static String describe(Exception e) {
        String message = e.getClass().getSimpleName();
        if (e.getMessage() != null) {
            message = message + ": " + e.getMessage();
        }
        if (message.length() > MAX_ERROR_LENGTH) {
            message = message.substring(0, MAX_ERROR_LENGTH);
        }
        return message;
    }
}
