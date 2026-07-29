package com.neobank.orchestrator.products;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.neobank.orchestrator.products.ProductCatalogue.CatalogueEntry;
import com.neobank.orchestrator.saga.ServiceRegistry;
import com.neobank.orchestrator.saga.ServiceRegistry.ServiceDef;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Reading the product catalogue out of the module that owns it.
 *
 * <p><b>Why this class exists.</b> Which version of a product counts is neo-01's rule, and it is
 * not the obvious one: the highest version wins, and it is then checked for {@code active}, so the
 * newest row in the seed files can be the wrong answer. Working that out by reading its changesets
 * produced the wrong catalogue twice — once by missing an {@code active: false} on the latest
 * version, once by missing a later file that superseded it. The code reads neo-01's own
 * {@code current} and {@code active} flags instead of re-deriving anything, and these tests pin
 * that it does.</p>
 *
 * <p>Without them, a rendered page proves nothing either way: the fallback list in
 * {@code products.js} carries the same two codes and the same ranges, so a picker showing the
 * right numbers is exactly what a completely broken read also looks like.</p>
 */
class ProductCatalogueTest {

    private static final String BASE = "http://neo-01:8080";

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private ProductCatalogue catalogue;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        ServiceRegistry registry = new ServiceRegistry(List.of(
                new ServiceDef(1, "neo01", "Application Verification", BASE),
                new ServiceDef(6, "neo06", "Agreement Management", "http://neo-06:8080")));
        catalogue = new ProductCatalogue(builder.build(), registry, "neo01");
    }

    /** One version row in neo-01's shape. */
    private static String version(int number, int min, int max, boolean active, boolean current) {
        return """
                {"productCode":"CREDIT_CARD_REWARDS","version":%d,"minAge":18,
                 "limitMin":%d,"limitMax":%d,"active":%b,
                 "channels":["WEB","MOBILE_APP"],"effectiveFrom":"2026-07-01T00:00:00Z",
                 "current":%b}"""
                .formatted(number, min, max, active, current);
    }

    private void expectCodes(String... codes) {
        String body = "[" + String.join(",",
                java.util.Arrays.stream(codes).map(c -> "\"" + c + "\"").toList()) + "]";
        server.expect(requestTo(BASE + "/products"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectVersions(String code, String... versions) {
        server.expect(requestTo(BASE + "/products/" + code + "/versions"))
                .andRespond(withSuccess("[" + String.join(",", versions) + "]",
                        MediaType.APPLICATION_JSON));
    }

    @Test
    void takesTheVersionTheModuleMarksCurrentAndNotTheHighestNumberItCanSee() {
        expectCodes("CREDIT_CARD_REWARDS");
        // Deliberately out of order and with a higher number that is NOT current — which is
        // what an ordered read of the changesets would have picked.
        expectVersions("CREDIT_CARD_REWARDS",
                version(6, 500, 10000, true, true),
                version(1, 1000, 10000, true, false),
                version(3, 1500, 12000, true, false));

        assertThat(catalogue.current())
                .singleElement()
                .extracting(CatalogueEntry::limitMin, CatalogueEntry::limitMax)
                .containsExactly(500, 10000);
        server.verify();
    }

    /**
     * The exact fault that made the first reading of this catalogue wrong: the latest version of
     * REWARDS was {@code active: false}, so the product was not on sale at all despite having the
     * newest row in the seed file.
     */
    @Test
    void aCurrentVersionThatIsNotActiveMeansTheProductIsNotOnSale() {
        expectCodes("CREDIT_CARD_REWARDS");
        expectVersions("CREDIT_CARD_REWARDS",
                version(1, 1000, 10000, true, false),
                version(4, 2000, 15000, false, true));

        assertThat(catalogue.current()).isEmpty();
        server.verify();
    }

    @Test
    void everyProductTheModuleSellsComesBackWithItsOwnRange() {
        expectCodes("CREDIT_CARD_STANDARD", "CREDIT_CARD_REWARDS");
        expectVersions("CREDIT_CARD_STANDARD",
                version(3, 250, 5000, true, true).replace("REWARDS", "STANDARD"));
        expectVersions("CREDIT_CARD_REWARDS", version(6, 500, 10000, true, true));

        assertThat(catalogue.current())
                .extracting(CatalogueEntry::productCode, CatalogueEntry::limitMin,
                        CatalogueEntry::limitMax)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("CREDIT_CARD_STANDARD", 250, 5000),
                        org.assertj.core.groups.Tuple.tuple("CREDIT_CARD_REWARDS", 500, 10000));
        server.verify();
    }

    /** One unhappy product must not empty the shop window. */
    @Test
    void aProductWhoseVersionsCannotBeReadIsSkippedAndTheRestSurvive() {
        expectCodes("CREDIT_CARD_STANDARD", "CREDIT_CARD_BROKEN");
        expectVersions("CREDIT_CARD_STANDARD",
                version(3, 250, 5000, true, true).replace("REWARDS", "STANDARD"));
        server.expect(requestTo(BASE + "/products/CREDIT_CARD_BROKEN/versions"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(catalogue.current())
                .singleElement()
                .extracting(CatalogueEntry::productCode)
                .isEqualTo("CREDIT_CARD_STANDARD");
    }

    /**
     * Empty, never an exception. A bank whose verification service is down should still be able
     * to show somebody a credit card — the picker keeps its own copy for exactly this.
     */
    @Test
    void anUnreachableModuleIsAnEmptyCatalogueAndNotAnError() {
        server.expect(requestTo(BASE + "/products"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(catalogue.current()).isEmpty();
    }

    @Test
    void aServiceMissingFromTheJourneyIsEmptyRatherThanNullPointing() {
        ProductCatalogue orphan = new ProductCatalogue(builder.build(),
                new ServiceRegistry(List.of()), "neo01");

        assertThat(orphan.current()).isEmpty();
    }

    /** No product has no versions at all; a module answering an empty list is not a crash. */
    @Test
    void aProductWithNoCurrentVersionIsSkipped() {
        expectCodes("CREDIT_CARD_REWARDS");
        expectVersions("CREDIT_CARD_REWARDS");

        assertThat(catalogue.current()).isEmpty();
    }
}
