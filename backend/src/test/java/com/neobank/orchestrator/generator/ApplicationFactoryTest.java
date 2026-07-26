package com.neobank.orchestrator.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The payload every service receives. It has to match {@code api-contract.md} §4 and be
 * reproducible — the whole point of seeding is that two runs produce the same applicants.
 */
class ApplicationFactoryTest {

    @Test
    void producesTheContractShape() {
        Map<String, Object> app = new ApplicationFactory(42).next("APP-0001");

        assertThat(app).containsKeys("applicationId", "channel", "submittedAt", "applicant",
                "identityDocument", "employment", "finances", "product", "delivery", "consents");
        assertThat(app.get("applicationId")).isEqualTo("APP-0001");
        assertThat(app.get("channel")).isIn("WEB", "MOBILE_APP", "BRANCH", "AGGREGATOR");

        assertThat(nested(app, "applicant")).containsKeys("fullName", "dateOfBirth", "email",
                "nationality", "countryOfResidence", "taxResidencies", "currentAddress");
        assertThat(nested(app, "product")).containsKeys("productCode", "requestedCreditLimit");
        assertThat(nested(app, "product").get("productCode")).isIn(
                "CREDIT_CARD_PREMIUM", "CREDIT_CARD_PLATINUM");
    }

    @Test
    void requestedLimitStaysInsideTheProductRange() {
        ApplicationFactory factory = new ApplicationFactory(42);

        IntStream.range(0, 200).forEach(i -> {
            Map<String, Object> app = factory.next("APP-" + i);
            Map<String, Object> product = nested(app, "product");
            int limit = (int) product.get("requestedCreditLimit");
            int max = switch ((String) product.get("productCode")) {
                case "CREDIT_CARD_PREMIUM" -> 10000;
                default -> 25000; // CREDIT_CARD_PLATINUM
            };
            assertThat(limit).isBetween(50, max);
        });
    }

    @Test
    void sameSeedProducesTheSameApplicants() {
        assertThat(applicantsFrom(42)).isEqualTo(applicantsFrom(42));
        assertThat(applicantsFrom(42)).isNotEqualTo(applicantsFrom(7));
    }

    private static List<Object> applicantsFrom(long seed) {
        ApplicationFactory factory = new ApplicationFactory(seed);
        return IntStream.range(0, 12)
                .mapToObj(i -> nested(factory.next("APP-" + i), "applicant").get("fullName"))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> app, String key) {
        return (Map<String, Object>) app.get(key);
    }
}
