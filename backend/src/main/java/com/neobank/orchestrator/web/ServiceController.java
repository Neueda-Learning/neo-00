package com.neobank.orchestrator.web;

import com.neobank.orchestrator.saga.SagaDtos.ServiceSummary;
import com.neobank.orchestrator.saga.SagaStore;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The second screen: one box per service, with how many applications it is currently
 * holding (dispatched, no callback yet) and how many of each decision it has given.
 */
@RestController
@RequestMapping("/api/v1/services")
public class ServiceController {

    private final SagaStore store;

    public ServiceController(SagaStore store) {
        this.store = store;
    }

    @GetMapping
    public List<ServiceSummary> services() {
        return store.serviceSummaries();
    }
}
