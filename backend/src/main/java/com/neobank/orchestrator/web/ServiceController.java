package com.neobank.orchestrator.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.neobank.orchestrator.ops.MonitoringRegistry;
import com.neobank.orchestrator.ops.MonitoringRegistry.MonitoredService;
import com.neobank.orchestrator.ops.ServiceHealthMonitor;
import com.neobank.orchestrator.saga.SagaDtos.ServiceSummary;
import com.neobank.orchestrator.saga.SagaStore;
import com.neobank.orchestrator.saga.ServiceRegistry;
import com.neobank.orchestrator.saga.ServiceRegistry.ServiceDef;
import java.util.ArrayList;
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
    private final ServiceRegistry registry;
    private final MonitoringRegistry monitoring;
    private final ServiceHealthMonitor health;

    public ServiceController(SagaStore store, ServiceRegistry registry,
                             MonitoringRegistry monitoring, ServiceHealthMonitor health) {
        this.store = store;
        this.registry = registry;
        this.monitoring = monitoring;
        this.health = health;
    }

    @GetMapping
    public List<ServiceView> services() {
        List<ServiceView> views = new ArrayList<>();
        store.serviceSummaries().forEach(summary -> {
            ServiceDef service = registry.byServiceId(summary.serviceId());
            views.add(ServiceView.from(summary,
                    health.status(service.serviceId(), service.baseUrl())));
        });
        monitoring.ordered().forEach(service -> views.add(ServiceView.monitoring(
                service, health.status(service.serviceId(), service.baseUrl()))));
        return views;
    }

    /** Traffic totals plus the cached reachability of the module's own API. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ServiceView(
            Integer step,
            String serviceId,
            String name,
            String baseUrl,
            String apiHealth,
            boolean monitoringOnly,
            Long inProgress,
            Long accepted,
            Long rejected,
            Long referred,
            Long timedOut,
            Long total) {

        static ServiceView from(ServiceSummary summary, String apiHealth) {
            return new ServiceView(
                    summary.step(), summary.serviceId(), summary.name(), summary.baseUrl(),
                    apiHealth, false, summary.inProgress(), summary.accepted(), summary.rejected(),
                    summary.referred(), summary.timedOut(), summary.total());
        }

        static ServiceView monitoring(MonitoredService service, String apiHealth) {
            return new ServiceView(null, service.serviceId(), service.name(), service.baseUrl(),
                    apiHealth, true, null, null, null, null, null, null);
        }
    }
}
