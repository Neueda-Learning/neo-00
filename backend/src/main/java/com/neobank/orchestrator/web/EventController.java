package com.neobank.orchestrator.web;

import com.neobank.orchestrator.saga.SagaDtos.EventView;
import com.neobank.orchestrator.saga.SagaStore;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The append-only log, newest first. With no {@code serviceId} it is everything that has
 * happened; with one, it is the "events per service" view.
 */
@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final SagaStore store;

    public EventController(SagaStore store) {
        this.store = store;
    }

    @GetMapping
    public List<EventView> events(@RequestParam(required = false) String serviceId,
                                  @RequestParam(defaultValue = "200") int limit) {
        return store.events(serviceId, Math.clamp(limit, 1, 2000)).stream()
                .map(EventView::from)
                .toList();
    }
}
