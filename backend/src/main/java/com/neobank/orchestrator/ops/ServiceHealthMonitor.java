package com.neobank.orchestrator.ops;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Small, non-blocking cache of each journey service's public {@code /health} endpoint.
 *
 * <p>The board refreshes every second, but health does not need to. An expired entry starts one
 * background probe and the request returns the last known state immediately.</p>
 */
@Service
public class ServiceHealthMonitor {

    private static final Duration FRESH_FOR = Duration.ofSeconds(10);

    private record Snapshot(String status, Instant checkedAt) {
    }

    private final RestClient http;
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Set<String> refreshing = ConcurrentHashMap.newKeySet();

    public ServiceHealthMonitor(RestClient http) {
        this.http = http;
    }

    public String status(String serviceId, String baseUrl) {
        Snapshot current = snapshots.get(serviceId);
        if (current == null || current.checkedAt().plus(FRESH_FOR).isBefore(Instant.now())) {
            refresh(serviceId, baseUrl);
        }
        return current == null ? "CHECKING" : current.status();
    }

    private void refresh(String serviceId, String baseUrl) {
        if (!refreshing.add(serviceId)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> body = http.get()
                        .uri(baseUrl + "/health")
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {});
                String reported = body == null ? null : String.valueOf(body.get("status"));
                snapshots.put(serviceId,
                        new Snapshot("UP".equalsIgnoreCase(reported) ? "UP" : "DOWN", Instant.now()));
            } catch (Exception e) {
                snapshots.put(serviceId, new Snapshot("DOWN", Instant.now()));
            } finally {
                refreshing.remove(serviceId);
            }
        });
    }
}
