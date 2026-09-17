package com.relay;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/endpoints")
public class EndpointController {

    private static final int MAX_PAGE = 500;

    private final EndpointRepository endpoints;
    private final DeliveryRepository deliveries;
    private final boolean allowPrivateHosts;

    EndpointController(EndpointRepository endpoints,
                       DeliveryRepository deliveries,
                       @Value("${relay.endpoints.allow-private-hosts}") boolean allowPrivateHosts) {
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.allowPrivateHosts = allowPrivateHosts;
    }

    record RegisterEndpoint(String url, List<String> eventTypes) {
    }

    record RegisteredEndpoint(UUID id, String url, List<String> eventTypes, boolean active, String secret) {
    }

    record EndpointView(UUID id, String url, List<String> eventTypes, boolean active, Instant createdAt) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    RegisteredEndpoint register(@RequestBody RegisterEndpoint request) {
        String url = requireDeliverableUrl(request.url());
        List<String> eventTypes = cleanEventTypes(request.eventTypes());

        Endpoint saved = endpoints.insert(url, Signing.newSecret(), eventTypes);
        return new RegisteredEndpoint(saved.id(), saved.url(), saved.eventTypes(), saved.active(), saved.secret());
    }

    @GetMapping
    List<EndpointView> list() {
        List<EndpointView> views = new ArrayList<>();
        for (Endpoint endpoint : endpoints.findAll()) {
            views.add(new EndpointView(
                    endpoint.id(),
                    endpoint.url(),
                    endpoint.eventTypes(),
                    endpoint.active(),
                    endpoint.createdAt()));
        }
        return views;
    }

    @GetMapping("/{id}/deliveries")
    List<DeliveryLogRow> deliveryLog(@PathVariable UUID id, @RequestParam(defaultValue = "100") int limit) {
        endpoints.findById(id).orElseThrow(() -> new NoSuchElementException("no endpoint " + id));
        return deliveries.findByEndpoint(id, Math.min(limit, MAX_PAGE));
    }

    private String requireDeliverableUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }

        URI url;
        try {
            url = new URI(rawUrl.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("url is not a valid URI");
        }

        boolean isHttp = "http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme());
        if (!isHttp || url.getHost() == null) {
            throw new IllegalArgumentException("url must be an absolute http(s) URL");
        }
        requirePublicHost(url.getHost());
        return url.toString();
    }

    /**
     * Anyone can register an endpoint, and the worker will POST to whatever it says, so a
     * private address would turn this service into a probe for the network it runs in.
     * Resolving here only settles what the name means right now — a host that resolves
     * publicly at registration can point somewhere private by delivery time, which is why
     * a hardened deployment repeats this check as it connects.
     */
    private void requirePublicHost(String host) {
        if (allowPrivateHosts) {
            return;
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("url host does not resolve: " + host);
        }

        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress()
                    || address.isAnyLocalAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                throw new IllegalArgumentException("url must resolve to a public address");
            }
        }
    }

    private static List<String> cleanEventTypes(List<String> requested) {
        if (requested == null) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>();
        for (String eventType : requested) {
            if (eventType != null && !eventType.isBlank()) {
                cleaned.add(eventType.trim());
            }
        }
        return cleaned;
    }
}
