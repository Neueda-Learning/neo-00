package com.neobank.orchestrator.simulator;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configured-only simulator targets: never accept a caller-supplied URL. */
@ConfigurationProperties("orchestrator.simulator")
public record SimulatorProperties(boolean enabled, List<Target> targets) {

    public SimulatorProperties {
        targets = targets == null ? List.of() : List.copyOf(targets);
    }

    public record Target(String serviceId, String name, String baseUrl, boolean analytical) {
    }

    public Target target(String serviceId) {
        return targets.stream()
                .filter(target -> target.serviceId().equals(serviceId))
                .findFirst()
                .orElse(null);
    }
}
