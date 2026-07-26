package com.neobank.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * neo-00 — the orchestrator.
 *
 * <p>Creates applications (on a toggle), walks each one through the ten services in order
 * waiting for a callback at every step, and keeps the append-only log that both front-end
 * screens are drawn from.</p>
 */
@SpringBootApplication
public class OrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
