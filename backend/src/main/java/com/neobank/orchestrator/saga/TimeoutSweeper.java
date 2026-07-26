package com.neobank.orchestrator.saga;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nothing else notices a service that never calls back. Without this, one dropped callback
 * leaves an application {@code IN_PROGRESS} for the life of the process and one service
 * permanently "in progress" on the summary screen.
 */
@Component
public class TimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(TimeoutSweeper.class);

    private final SagaEngine engine;
    private final Duration timeout;

    public TimeoutSweeper(SagaEngine engine,
                          @Value("${orchestrator.callback-timeout:30s}") Duration timeout) {
        this.engine = engine;
        this.timeout = timeout;
    }

    @Scheduled(fixedDelayString = "${orchestrator.sweep-interval-ms:5000}")
    void sweep() {
        try {
            int swept = engine.sweepTimeouts(timeout);
            if (swept > 0) {
                log.warn("Timed out {} application(s) after {} of silence", swept, timeout);
            }
        } catch (Exception e) {
            // Never let a sweep failure kill the scheduled task.
            log.error("Timeout sweep failed: {}", e.toString());
        }
    }
}
