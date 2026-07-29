package com.neobank.orchestrator.web;

import com.neobank.orchestrator.saga.SagaDtos.DemoRequest;
import com.neobank.orchestrator.saga.SagaDtos.DemoState;
import com.neobank.orchestrator.saga.SagaEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The demo-stepping toggle. While it is on, no step is dispatched until someone presses Proceed
 * on the board — see {@link SagaEngine}.
 *
 * <p><b>Orchestrator-only surface.</b> Nothing a module talks to changes: the dispatch envelope,
 * the {@code 202} ack and the status-update {@code PUT} are exactly as they were.</p>
 *
 * <p>Turning it <em>off</em> releases every journey currently parked, so an abandoned demo cannot
 * leave applications waiting on a click that never comes.</p>
 */
@RestController
@RequestMapping("/api/v1/demo-mode")
public class DemoController {

    private final SagaEngine engine;

    public DemoController(SagaEngine engine) {
        this.engine = engine;
    }

    @GetMapping
    public DemoState state() {
        return engine.demoState();
    }

    @PostMapping
    public DemoState update(@RequestBody DemoRequest body) {
        return body.enabled() == null ? engine.demoState() : engine.setDemoStepping(body.enabled());
    }
}
