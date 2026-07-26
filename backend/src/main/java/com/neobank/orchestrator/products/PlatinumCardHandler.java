package com.neobank.orchestrator.products;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** The Platinum tier. Record-only — it announces the routing and does nothing else. */
@Service
public class PlatinumCardHandler implements ProductHandler {

    private static final Logger log = LoggerFactory.getLogger(PlatinumCardHandler.class);

    @Override
    public String productCode() {
        return "CREDIT_CARD_PLATINUM";
    }

    @Override
    public void handle(String applicationId, Map<String, Object> application) {
        log.info("PLATINUM handler · {} · applicant='{}' — premium-tier onboarding",
                applicationId, ProductHandler.applicantName(application));
    }
}
