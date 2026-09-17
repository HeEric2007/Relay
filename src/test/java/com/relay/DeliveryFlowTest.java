package com.relay;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class DeliveryFlowTest extends IntegrationTest {

    private static final String HOOK = "/hook";

    private WireMockServer receiver;

    @BeforeEach
    void startReceiver() {
        receiver = new WireMockServer(options().dynamicPort());
        receiver.start();
    }

    @AfterEach
    void stopReceiver() {
        receiver.stop();
    }

    @Test
    void deliversTheEventAndSignsTheBodyWithTheEndpointSecret() {
        receiver.stubFor(post(HOOK).willReturn(aResponse().withStatus(200)));
        EndpointController.RegisteredEndpoint endpoint = register(List.of("invoice.paid"));

        EventController.SubmittedEvent submitted = submitEvent("invoice.paid", "{\"amount\":4200}");
        UUID deliveryId = onlyDeliveryOf(submitted);

        awaitStatus(deliveryId, "delivered");
        assertThat(fetch(deliveryId).attempts()).isEqualTo(1);
        assertThat(fetch(deliveryId).lastStatusCode()).isEqualTo(200);

        LoggedRequest received = receiver.findAll(postRequestedFor(urlEqualTo(HOOK))).get(0);
        assertThat(received.getHeader("Relay-Event-Id")).isEqualTo(submitted.eventId().toString());
        assertThat(received.getHeader("Relay-Event-Type")).isEqualTo("invoice.paid");
        assertThat(received.getHeader("Relay-Signature"))
                .isEqualTo(Signing.sign(endpoint.secret(), received.getBodyAsString()));
    }

    @Test
    void retriesWithBackoffUntilTheEndpointRecovers() {
        receiver.stubFor(post(HOOK).inScenario("flaky")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("still down"));
        receiver.stubFor(post(HOOK).inScenario("flaky")
                .whenScenarioStateIs("still down")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("back up"));
        receiver.stubFor(post(HOOK).inScenario("flaky")
                .whenScenarioStateIs("back up")
                .willReturn(aResponse().withStatus(200)));

        register(List.of("invoice.paid"));
        UUID deliveryId = onlyDeliveryOf(submitEvent("invoice.paid", "{\"amount\":1}"));

        awaitStatus(deliveryId, "delivered");
        Delivery delivered = fetch(deliveryId);
        assertThat(delivered.attempts()).isEqualTo(3);
        assertThat(delivered.lastStatusCode()).isEqualTo(200);
        assertThat(delivered.lastError()).isNull();
    }

    @Test
    void deadLettersAfterFiveAttemptsThenRetryBringsItBack() {
        receiver.stubFor(post(HOOK).willReturn(aResponse().withStatus(500)));
        register(List.of("invoice.paid"));
        UUID deliveryId = onlyDeliveryOf(submitEvent("invoice.paid", "{\"amount\":1}"));

        awaitStatus(deliveryId, "dead");
        Delivery dead = fetch(deliveryId);
        assertThat(dead.attempts()).isEqualTo(5);
        assertThat(dead.lastStatusCode()).isEqualTo(500);
        assertThat(receiver.findAll(postRequestedFor(urlEqualTo(HOOK)))).hasSize(5);

        receiver.resetAll();
        receiver.stubFor(post(HOOK).willReturn(aResponse().withStatus(200)));

        ResponseEntity<Delivery> retried = http.postForEntity(
                "/deliveries/" + deliveryId + "/retry", null, Delivery.class);

        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        awaitStatus(deliveryId, "delivered");
    }

    @Test
    void retryingADeliveryThatIsNotDeadConflicts() {
        receiver.stubFor(post(HOOK).willReturn(aResponse().withStatus(200)));
        register(List.of("invoice.paid"));
        UUID deliveryId = onlyDeliveryOf(submitEvent("invoice.paid", "{\"amount\":1}"));
        awaitStatus(deliveryId, "delivered");

        ResponseEntity<String> response = http.postForEntity(
                "/deliveries/" + deliveryId + "/retry", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void fansOutOnlyToEndpointsSubscribedToTheEventType() {
        receiver.stubFor(post(HOOK).willReturn(aResponse().withStatus(200)));
        register(List.of("invoice.paid"));
        register(List.of());

        EventController.SubmittedEvent subscribed = submitEvent("invoice.paid", "{\"n\":1}");
        EventController.SubmittedEvent unsubscribed = submitEvent("user.deleted", "{\"n\":2}");

        assertThat(subscribed.deliveryIds()).hasSize(2);
        assertThat(unsubscribed.deliveryIds()).hasSize(1);
    }

    @Test
    void theDeliveryLogNamesTheEventAndThePayloadIsReadable() {
        receiver.stubFor(post(HOOK).willReturn(aResponse().withStatus(200)));
        EndpointController.RegisteredEndpoint endpoint = register(List.of("invoice.paid"));

        EventController.SubmittedEvent submitted = submitEvent("invoice.paid", "{\"amount\":4200}");
        awaitStatus(onlyDeliveryOf(submitted), "delivered");

        DeliveryLogRow[] log = http.getForObject(
                "/endpoints/" + endpoint.id() + "/deliveries", DeliveryLogRow[].class);

        assertThat(log).hasSize(1);
        assertThat(log[0].eventType()).isEqualTo("invoice.paid");
        assertThat(log[0].eventId()).isEqualTo(submitted.eventId());

        EventController.EventView event = http.getForObject(
                "/events/" + submitted.eventId(), EventController.EventView.class);

        assertThat(event.type()).isEqualTo("invoice.paid");
        assertThat(event.payload().get("amount").asInt()).isEqualTo(4200);
    }

    @Test
    void rejectsAnEndpointUrlThatIsNotHttp() {
        ResponseEntity<String> response = http.postForEntity(
                "/endpoints", Map.of("url", "ftp://example.test/hook"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private EndpointController.RegisteredEndpoint register(List<String> eventTypes) {
        Map<String, Object> body = Map.of("url", receiver.baseUrl() + HOOK, "eventTypes", eventTypes);
        ResponseEntity<EndpointController.RegisteredEndpoint> response = http.postForEntity(
                "/endpoints", body, EndpointController.RegisteredEndpoint.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private EventController.SubmittedEvent submitEvent(String type, String payloadJson) {
        String body = "{\"type\":\"" + type + "\",\"payload\":" + payloadJson + "}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<EventController.SubmittedEvent> response = http.exchange(
                "/events", HttpMethod.POST, new HttpEntity<>(body, headers),
                EventController.SubmittedEvent.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return response.getBody();
    }

    private UUID onlyDeliveryOf(EventController.SubmittedEvent submitted) {
        assertThat(submitted.deliveryIds()).hasSize(1);
        return submitted.deliveryIds().get(0);
    }

    private Delivery fetch(UUID deliveryId) {
        return http.getForObject("/deliveries/" + deliveryId, Delivery.class);
    }

    private void awaitStatus(UUID deliveryId, String status) {
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> assertThat(fetch(deliveryId).status()).isEqualTo(status));
    }
}
