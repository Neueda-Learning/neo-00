package com.neobank.orchestrator.saga;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The ten services in order, from {@code orchestrator.services[]} in application.yml
 * (base URLs overridden per environment by {@code SERVICE_01_URL}…).
 *
 * <p>List order <em>is</em> the sequence; {@code step} is 1-based. Note that
 * {@code serviceId} ({@code neo01}) is not the repo name ({@code neo-01}) — the
 * ids are what services send on callbacks, so they are the ones that matter here.</p>
 */
@ConfigurationProperties(prefix = "orchestrator")
public record ServiceRegistry(List<ServiceDef> services) {

    public record ServiceDef(int step, String serviceId, String name, String baseUrl) {
    }

    public int size() {
        return services == null ? 0 : services.size();
    }

    /** The service at a 1-based step, or null if the step is out of range. */
    public ServiceDef byStep(int step) {
        if (services == null || step < 1 || step > services.size()) {
            return null;
        }
        return services.get(step - 1);
    }

    public List<ServiceDef> ordered() {
        return services == null ? List.of() : services;
    }
}
