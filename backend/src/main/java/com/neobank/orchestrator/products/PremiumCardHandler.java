package com.neobank.orchestrator.products;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** The Premium tier. Record-only — it announces the routing and does nothing else. */
@Service
public class PremiumCardHandler implements ProductHandler {

    private static final Logger log = LoggerFactory.getLogger(PremiumCardHandler.class);

    @Override
    public String productCode() {
        return "CREDIT_CARD_PREMIUM";
    }

    @Override
    public void handle(String applicationId, Map<String, Object> application) {
        log.info("PREMIUM handler · {} · applicant='{}' — everyday-tier onboarding",
                applicationId, ProductHandler.applicantName(application));
    }
}
