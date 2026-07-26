package com.neobank.orchestrator.products;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The router differentiates by product code and never lets an unknown product block a journey. */
class ProductRouterTest {

    /** A stand-in handler that records the last application id it was asked to handle. */
    private static final class RecordingHandler implements ProductHandler {
        private final String code;
        private String handled;

        RecordingHandler(String code) {
            this.code = code;
        }

        @Override
        public String productCode() {
            return code;
        }

        @Override
        public void handle(String applicationId, Map<String, Object> application) {
            this.handled = applicationId;
        }
    }

    private static Map<String, Object> application(String productCode) {
        return Map.of(
                "applicant", Map.of("fullName", "Maria Nowak"),
                "product", Map.of("productCode", productCode));
    }

    @Test
    void routesEachProductToItsOwnHandler() {
        RecordingHandler platinum = new RecordingHandler("CREDIT_CARD_PLATINUM");
        RecordingHandler premium = new RecordingHandler("CREDIT_CARD_PREMIUM");
        ProductRouter router = new ProductRouter(List.of(platinum, premium));

        router.route("CREDIT_CARD_PLATINUM", "APP-0001", application("CREDIT_CARD_PLATINUM"));
        router.route("CREDIT_CARD_PREMIUM", "APP-0002", application("CREDIT_CARD_PREMIUM"));

        assertThat(platinum.handled).isEqualTo("APP-0001");
        assertThat(premium.handled).isEqualTo("APP-0002");
    }

    @Test
    void unknownOrAbsentProductIsSkippedNotThrown() {
        RecordingHandler platinum = new RecordingHandler("CREDIT_CARD_PLATINUM");
        ProductRouter router = new ProductRouter(List.of(platinum));

        assertThatCode(() ->
                router.route("CREDIT_CARD_MYSTERY", "APP-0003", application("CREDIT_CARD_MYSTERY")))
                .doesNotThrowAnyException();
        assertThatCode(() -> router.route(null, "APP-0004", Map.of())).doesNotThrowAnyException();

        assertThat(platinum.handled).isNull();
    }

    @Test
    void realHandlersAnnounceTheirProductCode() {
        assertThat(new PlatinumCardHandler().productCode()).isEqualTo("CREDIT_CARD_PLATINUM");
        assertThat(new PremiumCardHandler().productCode()).isEqualTo("CREDIT_CARD_PREMIUM");
    }
}
