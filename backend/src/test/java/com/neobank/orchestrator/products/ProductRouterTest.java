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
        RecordingHandler rewards = new RecordingHandler("CREDIT_CARD_REWARDS");
        RecordingHandler standard = new RecordingHandler("CREDIT_CARD_STANDARD");
        ProductRouter router = new ProductRouter(List.of(rewards, standard));

        router.route("CREDIT_CARD_REWARDS", "APP-0001", application("CREDIT_CARD_REWARDS"));
        router.route("CREDIT_CARD_STANDARD", "APP-0002", application("CREDIT_CARD_STANDARD"));

        assertThat(rewards.handled).isEqualTo("APP-0001");
        assertThat(standard.handled).isEqualTo("APP-0002");
    }

    @Test
    void unknownOrAbsentProductIsSkippedNotThrown() {
        RecordingHandler rewards = new RecordingHandler("CREDIT_CARD_REWARDS");
        ProductRouter router = new ProductRouter(List.of(rewards));

        assertThatCode(() ->
                router.route("CREDIT_CARD_MYSTERY", "APP-0003", application("CREDIT_CARD_MYSTERY")))
                .doesNotThrowAnyException();
        assertThatCode(() -> router.route(null, "APP-0004", Map.of())).doesNotThrowAnyException();

        assertThat(rewards.handled).isNull();
    }

    @Test
    void realHandlersAnnounceTheirProductCode() {
        assertThat(new RewardsCardHandler().productCode()).isEqualTo("CREDIT_CARD_REWARDS");
        assertThat(new StandardCardHandler().productCode()).isEqualTo("CREDIT_CARD_STANDARD");
    }
}
