package com.neobank.orchestrator.products;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** The Rewards tier. Record-only — it announces the routing and does nothing else. */
@Service
public class RewardsCardHandler implements ProductHandler {

    private static final Logger log = LoggerFactory.getLogger(RewardsCardHandler.class);

    @Override
    public String productCode() {
        return "CREDIT_CARD_REWARDS";
    }

    @Override
    public void handle(String applicationId, Map<String, Object> application) {
        log.info("REWARDS handler · {} · applicant='{}' — rewards-tier onboarding",
                applicationId, ProductHandler.applicantName(application));
    }
}
