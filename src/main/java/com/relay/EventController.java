package com.relay;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/events")
public class EventController {

    private final EventRepository events;
    private final DeliveryRepository deliveries;
    private final ObjectMapper json;

    EventController(EventRepository events, DeliveryRepository deliveries, ObjectMapper json) {
        this.events = events;
        this.deliveries = deliveries;
        this.json = json;
    }

    record SubmitEvent(String type, JsonNode payload) {
    }

    record SubmittedEvent(UUID eventId, String type, List<UUID> deliveryIds) {
    }

    record EventView(UUID id, String type, JsonNode payload, Instant createdAt) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public SubmittedEvent submit(@RequestBody SubmitEvent request) {
        if (request.type() == null || request.type().isBlank()) {
            throw new IllegalArgumentException("type is required");
        }
        if (request.payload() == null || request.payload().isNull()) {
            throw new IllegalArgumentException("payload is required");
        }

        Event event = events.insert(request.type().trim(), request.payload().toString());
        List<UUID> deliveryIds = deliveries.fanOut(event.id(), event.type());
        return new SubmittedEvent(event.id(), event.type(), deliveryIds);
    }

    @GetMapping("/{id}")
    public EventView get(@PathVariable UUID id) {
        Event event = events.findById(id)
                .orElseThrow(() -> new NoSuchElementException("no event " + id));

        // payload is stored as jsonb text; re-read it so it serialises as JSON, not a string.
        JsonNode payload;
        try {
            payload = json.readTree(event.payload());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("event " + id + " has an unreadable payload", e);
        }
        return new EventView(event.id(), event.type(), payload, event.createdAt());
    }
}
