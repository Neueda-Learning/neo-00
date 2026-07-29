package com.neobank.orchestrator.customer;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** What a customer's own screen is served. */
public final class CustomerDtos {

    private CustomerDtos() {
    }

    /**
     * What one of a customer's things IS — and the distinction the whole screen is built on.
     *
     * <p>An <b>application</b> is still being decided, waiting for a signature, or was refused.
     * A <b>product</b> is a card: the agreement was signed, so there is an account and a limit.
     * The same row becomes the second the moment it passes the signature step.</p>
     */
    public enum Kind {
        APPLICATION,
        PRODUCT
    }

    /**
     * One application or product on a customer's screen.
     *
     * <p>{@code outputs} is passed through <b>whole and untyped</b>, exactly as
     * {@code /journey} already serves it. Picking fields out here would be a second copy of
     * api-contract §3's key-ownership table, and typing them is the {@code ClassCastException}
     * that section warns about at length — a JSON number arrives as {@code Integer},
     * {@code Long} or {@code Double} depending on its magnitude. The front end reads keys off
     * JSON, where the problem does not exist.</p>
     *
     * <p>{@code awaitingSignature} and {@code pendingStep} are here because without them the one
     * row a customer must act on renders as an anonymous {@code IN_PROGRESS} with no button.</p>
     */
    public record CustomerItem(
            String applicationId,
            Kind kind,
            String productCode,
            Integer requestedLimit,
            String overallStatus,
            int currentStep,
            boolean awaitingSignature,
            Integer pendingStep,
            Map<String, Object> outputs,
            Instant createdAt,
            Instant updatedAt) {
    }

    /**
     * The answer to signing in.
     *
     * <p>{@code isNew} is what the greeting reads — "welcome back" or "let's get you a card". It
     * must come from here and never from the login screen's typing hint, which is a separate
     * request that can disagree with this one if a code is created in between.</p>
     */
    public record CustomerView(String customerId, boolean isNew, List<CustomerItem> items) {
    }
}
