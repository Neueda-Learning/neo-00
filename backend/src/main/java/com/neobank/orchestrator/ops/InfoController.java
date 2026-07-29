package com.neobank.orchestrator.ops;

import com.neobank.orchestrator.saga.SagaEngine;
import com.neobank.orchestrator.saga.ServiceRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /info} — the sequence as configured, and the timings that drive it. */
@RestController
public class InfoController {

    private final ServiceRegistry registry;
    private final SagaEngine engine;
    private final String callbackTimeout;

    public InfoController(ServiceRegistry registry, SagaEngine engine,
                          @Value("${orchestrator.callback-timeout:30s}") String callbackTimeout) {
        this.registry = registry;
        this.engine = engine;
        this.callbackTimeout = callbackTimeout;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "neo-00 orchestrator");
        body.put("steps", registry.size());
        body.put("stepDelay", engine.stepDelay().toString());
        body.put("callbackTimeout", callbackTimeout);
        // On means every dispatch is waiting for an operator — worth seeing here, because a
        // stack that looks stuck is usually a demo toggle somebody left on.
        body.put("demoStepping", engine.isDemoStepping());
        body.put("terminalStatuses", List.of(SagaEngine.terminalStatuses()));
        body.put("sequence", registry.ordered().stream()
                .map(s -> Map.of("step", s.step(), "serviceId", s.serviceId(),
                        "name", s.name(), "baseUrl", s.baseUrl()))
                .toList());
        return body;
    }
}
