package com.relay;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
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

    EventController(EventRepository events, DeliveryRepository deliveries) {
        this.events = events;
        this.deliveries = deliveries;
    }

    record SubmitEvent(String type, JsonNode payload) {
    }

    record SubmittedEvent(UUID eventId, String type, List<UUID> deliveryIds) {
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
}
