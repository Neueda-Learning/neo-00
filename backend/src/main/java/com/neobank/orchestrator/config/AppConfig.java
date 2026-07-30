package com.neobank.orchestrator.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestClient;

/** Infrastructure: the HTTP client that dispatches to services, and the pool that runs
 *  the delayed dispatches, the generator tick and the timeout sweep. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({
        com.neobank.orchestrator.saga.ServiceRegistry.class,
        com.neobank.orchestrator.ops.MonitoringRegistry.class,
        com.neobank.orchestrator.simulator.SimulatorProperties.class
})
public class AppConfig {

    /**
     * Short timeouts on purpose: a dispatch is supposed to return a {@code 202} within
     * milliseconds. Left at the default (infinite), one unreachable service would pin a
     * scheduler thread indefinitely and stall every other journey.
     */
    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return builder.requestFactory(factory).build();
    }

    /**
     * Several journeys are in flight at once, each with its own delayed dispatch, and the
     * generator and sweeper share this pool. A single thread would serialise them and make
     * the 1-second inter-step delay drift under load.
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(8);
        scheduler.setThreadNamePrefix("saga-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler;
    }
}
