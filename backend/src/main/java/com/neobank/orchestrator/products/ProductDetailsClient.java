package com.neobank.orchestrator.products;

import com.neobank.orchestrator.agreement.AgreementClient;
import com.neobank.orchestrator.agreement.AgreementClient.AgreementView;
import com.neobank.orchestrator.saga.ServiceRegistry;
import com.neobank.orchestrator.saga.ServiceRegistry.ServiceDef;
import java.math.BigDecimal;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Reads the few module-owned facts a customer needs after their card journey has completed.
 *
 * <p>The accumulated saga outputs remain the primary source. This adapter exists for modules
 * still sending the original three-field callback: it reads their own case APIs without copying
 * their business rules or databases into the platform.</p>
 */
@Service
public class ProductDetailsClient {

    private static final Logger log = LoggerFactory.getLogger(ProductDetailsClient.class);

    public record ProductDetailsView(
            Integer approvedLimit,
            BigDecimal apr,
            String accountId,
            String panLast4) {
    }

    private final RestClient http;
    private final ServiceRegistry registry;
    private final AgreementClient agreements;

    public ProductDetailsClient(RestClient http, ServiceRegistry registry,
                                AgreementClient agreements) {
        this.http = http;
        this.registry = registry;
        this.agreements = agreements;
    }

    public ProductDetailsView view(String applicationId) {
        AgreementView agreement = agreement(applicationId);
        Map<String, Object> account = caseView("neo07", applicationId);
        Map<String, Object> card = caseView("neo08", applicationId);

        return new ProductDetailsView(
                agreement == null ? null : agreement.approvedLimit(),
                agreement == null ? null : agreement.apr(),
                firstPresent(text(account, "accountId"), text(card, "accountId")),
                firstPresent(text(card, "panLast4"), lastFour(text(card, "panMasked"))));
    }

    private AgreementView agreement(String applicationId) {
        try {
            return agreements.view(applicationId);
        } catch (Exception e) {
            log.debug("Agreement details unavailable for {}: {}", applicationId, e.toString());
            return null;
        }
    }

    private Map<String, Object> caseView(String serviceId, String applicationId) {
        ServiceDef service = registry.byServiceId(serviceId);
        if (service == null) {
            return Map.of();
        }
        try {
            Map<String, Object> body = http.get()
                    .uri(service.baseUrl() + "/cases/" + applicationId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            return body == null ? Map.of() : body;
        } catch (Exception e) {
            log.debug("{} product details unavailable for {}: {}",
                    serviceId, applicationId, e.toString());
            return Map.of();
        }
    }

    private static String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static String lastFour(String maskedPan) {
        if (maskedPan == null) {
            return null;
        }
        String digits = maskedPan.replaceAll("\\D", "");
        return digits.length() < 4 ? null : digits.substring(digits.length() - 4);
    }

    private static String firstPresent(String preferred, String fallback) {
        return preferred == null ? fallback : preferred;
    }
}
