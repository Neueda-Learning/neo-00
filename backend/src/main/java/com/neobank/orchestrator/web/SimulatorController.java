package com.neobank.orchestrator.web;

import com.neobank.orchestrator.simulator.ScenarioLibrary;
import com.neobank.orchestrator.simulator.SimulationService;
import com.neobank.orchestrator.simulator.SimulationService.DispatchCommand;
import com.neobank.orchestrator.simulator.SimulationService.DispatchView;
import com.neobank.orchestrator.simulator.SimulatorProperties;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Instructor tooling. This API is not part of the contract modules implement or call. */
@RestController
@RequestMapping("/api/v1/simulator")
@ConditionalOnProperty(prefix = "orchestrator.simulator", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class SimulatorController {

    private final ScenarioLibrary scenarios;
    private final SimulatorProperties properties;
    private final SimulationService simulations;

    public SimulatorController(ScenarioLibrary scenarios, SimulatorProperties properties,
                               SimulationService simulations) {
        this.scenarios = scenarios;
        this.properties = properties;
        this.simulations = simulations;
    }

    @GetMapping("/scenarios")
    public Map<String, Object> scenarios() {
        return scenarios.catalogue();
    }

    @GetMapping("/targets")
    public List<SimulatorProperties.Target> targets() {
        return properties.targets();
    }

    @PostMapping("/dispatch")
    public DispatchView dispatch(@RequestBody DispatchCommand command) {
        return simulations.dispatch(command);
    }

    @GetMapping("/dispatches")
    public List<DispatchView> dispatches(@RequestParam(required = false) String target) {
        return simulations.dispatches(target);
    }

    @DeleteMapping("/dispatches")
    public Map<String, Long> clear(@RequestParam String target) {
        return Map.of("deleted", simulations.clear(target));
    }
}
