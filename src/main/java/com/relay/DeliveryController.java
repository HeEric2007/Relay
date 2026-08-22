package com.relay;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/deliveries")
public class DeliveryController {

    private static final int MAX_PAGE = 500;

    private final DeliveryRepository deliveries;

    DeliveryController(DeliveryRepository deliveries) {
        this.deliveries = deliveries;
    }

    record DeliveryBoard(Map<String, Long> counts, List<Delivery> deliveries) {
    }

    @GetMapping("/{id}")
    Delivery get(@PathVariable UUID id) {
        return deliveries.findById(id).orElseThrow(() -> new NoSuchElementException("no delivery " + id));
    }

    @GetMapping
    DeliveryBoard board(@RequestParam(defaultValue = "50") int limit) {
        return new DeliveryBoard(deliveries.countsByStatus(), deliveries.findRecent(Math.min(limit, MAX_PAGE)));
    }

    @PostMapping("/{id}/retry")
    ResponseEntity<Object> retry(@PathVariable UUID id) {
        Delivery delivery = deliveries.findById(id)
                .orElseThrow(() -> new NoSuchElementException("no delivery " + id));

        int requeued = deliveries.requeueFromDead(id);
        if (requeued == 0) {
            String error = "delivery is " + delivery.status() + "; only dead deliveries can be retried";
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", error));
        }
        return ResponseEntity.ok(deliveries.findById(id).orElseThrow());
    }
}
