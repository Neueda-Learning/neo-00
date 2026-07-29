package com.neobank.orchestrator.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.orchestrator.customer.CustomerDtos.Kind;
import com.neobank.orchestrator.domain.Application;
import com.neobank.orchestrator.domain.ApplicationRepository;
import com.neobank.orchestrator.domain.CustomerRepository;
import com.neobank.orchestrator.saga.ServiceRegistry;
import com.neobank.orchestrator.saga.ServiceRegistry.ServiceDef;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which of a customer's things are products, and which are still applications.
 *
 * <p><b>A unit test and not a driven journey, on purpose.</b> A journey only reaches the steps it
 * happens to reach — the ones this rule gets wrong are the ones where a service is unreachable or
 * a card bureau refuses, which a green stack never produces. Driving the saga would prove one row
 * at a time, by luck. The table is the thing worth pinning, so the table is the test.</p>
 */
class CustomerClassificationTest {

    /** neo06 at step 6, as the real journey has it. */
    private static final ServiceRegistry JOURNEY = new ServiceRegistry(List.of(
            new ServiceDef(1, "neo01", "Application Verification", "http://neo-01:8080"),
            new ServiceDef(6, "neo06", "Agreement Management", "http://neo-06:8080"),
            new ServiceDef(8, "neo08", "Card Issuing", "http://neo-08:8080")));

    private static CustomerService serviceWith(ServiceRegistry registry) {
        return new CustomerService(mock(CustomerRepository.class), mock(ApplicationRepository.class),
                new ObjectMapper(), registry, "neo06");
    }

    /** An application at a given step and status. The signature hold is set separately. */
    private static Application at(int step, String status) {
        Application app = new Application("APP-0001", "corr", "Ada Lovelace",
                "CREDIT_CARD_STANDARD", 3000, "WEB", "{}", "AB12");
        app.setCurrentStep(step);
        app.setOverallStatus(status);
        return app;
    }

    private Kind kind(int step, String status) {
        return serviceWith(JOURNEY).kindOf(at(step, status));
    }

    // ---- still an application ----

    @Test
    void beforeAndUpToTheSignatureStepItIsAnApplication() {
        assertThat(kind(0, Application.IN_PROGRESS)).isEqualTo(Kind.APPLICATION);
        assertThat(kind(3, Application.IN_PROGRESS)).isEqualTo(Kind.APPLICATION);
        assertThat(kind(6, Application.IN_PROGRESS)).isEqualTo(Kind.APPLICATION);
    }

    @Test
    void aRefusalIsAnApplicationWhereverItHappened() {
        assertThat(kind(3, Application.REJECTED)).isEqualTo(Kind.APPLICATION);
        assertThat(kind(5, Application.REFERRED)).isEqualTo(Kind.APPLICATION);
        assertThat(kind(2, Application.FAILED)).isEqualTo(Kind.APPLICATION);
    }

    @Test
    void waitingForTheCustomersSignatureIsStillAnApplication() {
        Application app = at(6, Application.IN_PROGRESS);
        app.setAwaitingSignatureAt(java.time.Instant.now());

        assertThat(serviceWith(JOURNEY).kindOf(app)).isEqualTo(Kind.APPLICATION);
    }

    /**
     * The two the naive rule gets wrong, and the reason this class exists.
     *
     * <p>{@code currentStep} is never rewound. A journey that dies at step 7 or 8 — the account
     * service unreachable, the sweeper giving up, the card bureau refusing — keeps
     * {@code currentStep = 7} for ever. "Past step six" alone would call that a product and show
     * somebody an account number that was never opened.</p>
     */
    @Test
    void aJourneyThatDiedAfterSigningIsAnApplicationAndNotAProduct() {
        assertThat(kind(7, Application.FAILED)).isEqualTo(Kind.APPLICATION);
        assertThat(kind(7, Application.REJECTED)).isEqualTo(Kind.APPLICATION);
        assertThat(kind(8, Application.FAILED)).isEqualTo(Kind.APPLICATION);
        assertThat(kind(8, Application.REFERRED)).isEqualTo(Kind.APPLICATION);
    }

    // ---- a product ----

    @Test
    void signedAndStillRunningIsAlreadyAProduct() {
        // The card is being set up. There is an approved limit and an account on the way, and
        // calling this an application would hide both until the last step finished.
        assertThat(kind(7, Application.IN_PROGRESS)).isEqualTo(Kind.PRODUCT);
    }

    @Test
    void completedIsAProduct() {
        assertThat(kind(8, Application.COMPLETED)).isEqualTo(Kind.PRODUCT);
    }

    // ---- misconfiguration ----

    /**
     * If the configured signature service is not in the journey, nothing can be known to be past
     * it. Understating what somebody has is the safe way to be wrong; the other way round invents
     * an account number.
     */
    @Test
    void withNoSignatureServiceConfiguredEverythingIsAnApplication() {
        CustomerService orphan = serviceWith(new ServiceRegistry(List.of(
                new ServiceDef(1, "neo01", "Application Verification", "http://neo-01:8080"))));

        assertThat(orphan.kindOf(at(8, Application.COMPLETED))).isEqualTo(Kind.APPLICATION);
        assertThat(orphan.kindOf(at(7, Application.IN_PROGRESS))).isEqualTo(Kind.APPLICATION);
    }
}
