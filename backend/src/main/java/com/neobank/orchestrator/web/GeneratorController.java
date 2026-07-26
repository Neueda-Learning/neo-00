package com.neobank.orchestrator.web;

import com.neobank.orchestrator.generator.GeneratorService;
import com.neobank.orchestrator.saga.SagaDtos.GeneratorRequest;
import com.neobank.orchestrator.saga.SagaDtos.GeneratorState;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The start/stop toggle. Both fields of the body are optional — send just the one you mean. */
@RestController
@RequestMapping("/api/v1/generator")
public class GeneratorController {

    private final GeneratorService generator;

    public GeneratorController(GeneratorService generator) {
        this.generator = generator;
    }

    @GetMapping
    public GeneratorState state() {
        return generator.state();
    }

    @PostMapping
    public GeneratorState update(@RequestBody GeneratorRequest body) {
        return generator.update(body.enabled(), body.intervalMs());
    }
}
