package com.neobank.orchestrator.products;

import java.util.Map;

/**
 * Handles one product's applications at ingest, before the journey starts.
 *
 * <p><b>Record-only.</b> A handler announces the routing and nothing more — it does not decide
 * the outcome (that still comes from the downstream service) and does not mutate the payload.
 * Adding a product is adding a {@code @Service ProductHandler}; {@link ProductRouter} wires it
 * in automatically.</p>
 */
public interface ProductHandler {

    /** The product code this handler owns, e.g. {@code CREDIT_CARD_REWARDS}. */
    String productCode();

    /** Called once per application of this product, just before its journey is dispatched. */
    void handle(String applicationId, Map<String, Object> application);

    /** Null-safe read of the applicant's full name from an application payload. */
    static String applicantName(Map<String, Object> application) {
        if (application != null && application.get("applicant") instanceof Map<?, ?> applicant) {
            Object name = applicant.get("fullName");
            if (name != null) {
                return name.toString();
            }
        }
        return "unknown";
    }
}
