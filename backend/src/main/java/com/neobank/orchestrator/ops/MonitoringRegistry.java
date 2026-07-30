package com.neobank.orchestrator.ops;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** APIs shown on the Services screen but deliberately excluded from the customer journey. */
@ConfigurationProperties(prefix = "monitoring")
public record MonitoringRegistry(List<MonitoredService> services) {

    public record MonitoredService(String serviceId, String name, String baseUrl) {
    }

    public List<MonitoredService> ordered() {
        return services == null ? List.of() : services;
    }
}
