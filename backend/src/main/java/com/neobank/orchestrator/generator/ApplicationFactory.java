package com.neobank.orchestrator.generator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds the Application object the orchestrator sends to every service — the shape from
 * {@code api-contract.md} §4, which is itself the hackathon's real payload.
 *
 * <p>Seeded with 42 (the course convention) so a run is reproducible: the same sequence of
 * applications, with the same applicants and products, every time.</p>
 *
 * <p>No service in attempt-01 reads these fields to decide anything. They exist so the
 * payload, the log lines and the event log look like the real system.</p>
 */
@Component
public class ApplicationFactory {

    private record Applicant(String fullName, String dateOfBirth, String nationality,
                             String city, String postcode, String employer) {
    }

    private static final List<Applicant> CAST = List.of(
            new Applicant("Maria Nowak", "1996-04-11", "PL", "London", "E1 5JP", "Trellis Health Ltd"),
            new Applicant("Tom Okafor", "1988-11-02", "NG", "Manchester", "M1 4BT", "Northgate Logistics"),
            new Applicant("Ana Ruiz", "1993-06-27", "ES", "Bristol", "BS1 6QF", "Harbour Analytics"),
            new Applicant("Jonas Meyer", "1979-02-14", "DE", "Leeds", "LS1 4DY", "Pennine Foods"),
            new Applicant("Sofia Bianchi", "2000-09-30", "IT", "Glasgow", "G2 1DU", "Clyde Robotics"),
            new Applicant("Peter Novak", "1985-01-19", "SK", "Cardiff", "CF10 1EP", "Severn Utilities"),
            new Applicant("Lena Kowalczyk", "1998-07-08", "PL", "Liverpool", "L1 8JQ", "Mersey Care Group"),
            new Applicant("Daniel Fischer", "1991-03-23", "DE", "Sheffield", "S1 2HE", "Steel City Media"));

    private static final List<String> CHANNELS = List.of("WEB", "MOBILE_APP", "BRANCH", "AGGREGATOR");

    /** The two-card catalogue this demo offers: code, min limit, max limit. */
    private record Product(String code, int minLimit, int maxLimit) {
    }

    // The customer journey offers exactly these two products, so the generated backoffice
    // fixtures use them too — the whole demo then speaks one product language. The board strips
    // the CREDIT_CARD_ prefix, rendering "PREMIUM" / "PLATINUM".
    private static final List<Product> PRODUCTS = List.of(
            new Product("CREDIT_CARD_PREMIUM", 500, 10000),
            new Product("CREDIT_CARD_PLATINUM", 5000, 25000));

    private final Random random;

    public ApplicationFactory(@Value("${orchestrator.generator.seed:42}") long seed) {
        this.random = new Random(seed);
    }

    /** One application payload, ready to be stored and sent. */
    public synchronized Map<String, Object> next(String applicationId) {
        Applicant who = CAST.get(random.nextInt(CAST.size()));
        Product product = PRODUCTS.get(random.nextInt(PRODUCTS.size()));
        String channel = CHANNELS.get(random.nextInt(CHANNELS.size()));
        int limit = roundToFifty(product.minLimit()
                + random.nextInt(product.maxLimit() - product.minLimit() + 1));
        int annualIncome = 18_000 + random.nextInt(60) * 1_000;

        Map<String, Object> application = new LinkedHashMap<>();
        application.put("applicationId", applicationId);
        application.put("channel", channel);
        application.put("submittedAt", Instant.now().toString());
        application.put("applicant", Map.of(
                "fullName", who.fullName(),
                "dateOfBirth", who.dateOfBirth(),
                "email", emailFor(who.fullName()),
                "mobile", "+4477009" + String.format("%05d", random.nextInt(100_000)),
                "nationality", who.nationality(),
                "countryOfResidence", "GB",
                "taxResidencies", List.of("GB"),
                "residentialStatus", pick("OWNER", "MORTGAGE", "RENTING", "LIVING_WITH_FAMILY"),
                "currentAddress", Map.of(
                        "line1", (1 + random.nextInt(99)) + " Hanbury Street",
                        "city", who.city(),
                        "postcode", who.postcode(),
                        "country", "GB"),
                "monthsAtAddress", 3 + random.nextInt(120)));
        application.put("identityDocument", Map.of(
                "type", pick("PASSPORT", "DRIVING_LICENCE", "NATIONAL_ID"),
                "documentId", "ZS" + (1_000_000 + random.nextInt(9_000_000)),
                "issuingCountry", who.nationality(),
                "expiryDate", LocalDate.of(2029 + random.nextInt(6), 1 + random.nextInt(12), 28)
                        .toString()));
        application.put("employment", Map.of(
                "status", pick("PERMANENT", "CONTRACT", "SELF_EMPLOYED", "STUDENT"),
                "employerName", who.employer(),
                "monthsInEmployment", 1 + random.nextInt(150)));
        application.put("finances", Map.of(
                "annualIncome", annualIncome,
                "monthlyHousingCost", 400 + random.nextInt(1_400),
                "existingCreditCommitments", random.nextInt(600)));
        application.put("product", Map.of(
                "productCode", product.code(),
                "requestedCreditLimit", limit));
        application.put("delivery", Map.of("useCurrentAddress", true));
        application.put("consents", Map.of(
                "termsAccepted", true,
                "paperlessStatements", random.nextBoolean(),
                "marketingConsent", random.nextBoolean()));
        return application;
    }

    private String pick(String... options) {
        return options[random.nextInt(options.length)];
    }

    private static int roundToFifty(int amount) {
        return Math.max(50, (amount / 50) * 50);
    }

    private static String emailFor(String fullName) {
        return fullName.toLowerCase().replace(' ', '.') + "@example.com";
    }
}
