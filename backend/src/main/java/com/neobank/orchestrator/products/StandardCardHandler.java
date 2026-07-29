package com.neobank.orchestrator.products;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** The Standard tier. Record-only — it announces the routing and does nothing else. */
@Service
public class StandardCardHandler implements ProductHandler {

    private static final Logger log = LoggerFactory.getLogger(StandardCardHandler.class);

    @Override
    public String productCode() {
        return "CREDIT_CARD_STANDARD";
    }

    @Override
    public void handle(String applicationId, Map<String, Object> application) {
        log.info("STANDARD handler · {} · applicant='{}' — everyday-tier onboarding",
                applicationId, ProductHandler.applicantName(application));
    }
}
