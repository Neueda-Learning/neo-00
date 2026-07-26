package com.neobank.orchestrator.generator;

import com.neobank.orchestrator.domain.Application;
import com.neobank.orchestrator.domain.ApplicationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Limit;

/**
 * Application ids are {@code APP-0001}, {@code APP-0002}… and the counter lives in memory,
 * so a restart against an existing database would otherwise re-issue ids that are already
 * taken. This reads the highest one back at boot and continues from there.
 */
@Configuration
public class GeneratorSeed {

    private static final Logger log = LoggerFactory.getLogger(GeneratorSeed.class);

    @Bean
    ApplicationRunner resumeApplicationNumbering(ApplicationRepository applications,
                                                 GeneratorService generator) {
        return args -> {
            long highest = applications.findAllByOrderByCreatedAtDesc(Limit.of(500)).stream()
                    .map(Application::getId)
                    .mapToLong(GeneratorSeed::numberIn)
                    .max()
                    .orElse(0L);
            if (highest > 0) {
                generator.resumeCountFrom(highest);
                log.info("Resuming application numbering after APP-{}",
                        String.format("%04d", highest));
            }
        };
    }

    /** {@code APP-0042} → 42; anything else → 0. */
    private static long numberIn(String applicationId) {
        int dash = applicationId.lastIndexOf('-');
        if (dash < 0) {
            return 0;
        }
        try {
            return Long.parseLong(applicationId.substring(dash + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
