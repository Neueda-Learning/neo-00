package com.neobank.orchestrator.customer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.orchestrator.customer.CustomerDtos.CustomerItem;
import com.neobank.orchestrator.customer.CustomerDtos.CustomerView;
import com.neobank.orchestrator.customer.CustomerDtos.Kind;
import com.neobank.orchestrator.domain.Application;
import com.neobank.orchestrator.domain.ApplicationRepository;
import com.neobank.orchestrator.domain.Customer;
import com.neobank.orchestrator.domain.CustomerRepository;
import com.neobank.orchestrator.saga.ServiceRegistry;
import com.neobank.orchestrator.saga.ServiceRegistry.ServiceDef;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A customer, and everything they have.
 *
 * <p><b>Reads the database and nothing else.</b> A customer with six things would otherwise cost
 * six calls to the support desk and six to the agreement service on every page load — and the
 * support panel polls every five seconds on top of that. Tickets and agreements are fetched one
 * at a time, when a customer opens one thing.</p>
 */
@Service
public class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    /**
     * Nobody has this many. It exists so a runaway cannot turn one customer's home into an
     * unbounded read; the screen is built to show everything under it.
     */
    private static final int MAX_ITEMS = 200;

    private final CustomerRepository customers;
    private final ApplicationRepository applications;
    private final ObjectMapper json;

    /**
     * Which step the customer signs at — the boundary between an application and a product.
     *
     * <p>Resolved once, from configuration, because the journey is defined in
     * {@code application.yml} and a step number written into Java here would be wrong the moment
     * somebody reorders it.</p>
     */
    private final int signatureStep;

    public CustomerService(CustomerRepository customers, ApplicationRepository applications,
                           ObjectMapper json, ServiceRegistry registry,
                           @Value("${orchestrator.signature.service-id:neo06}") String signatureServiceId) {
        this.customers = customers;
        this.applications = applications;
        this.json = json;
        ServiceDef signature = registry.byServiceId(signatureServiceId);
        if (signature == null) {
            // Everything then classifies as an application, which is the safe way to be wrong:
            // calling a real product an application understates what somebody has, while the
            // other way round shows them an account number that may never have been opened.
            log.warn("No service '{}' in the journey — every application will be shown as an "
                    + "application, never as a product. Check orchestrator.signature.service-id.",
                    signatureServiceId);
            this.signatureStep = Integer.MAX_VALUE;
        } else {
            this.signatureStep = signature.step();
        }
    }

    /**
     * Sign in. Creates the customer if the code is new; either way returns everything they have.
     *
     * <p>Idempotent, and one call rather than a lookup followed by a create — those two race, and
     * with no password there is nothing a second caller could be doing wrong anyway.</p>
     */
    @Transactional
    public CustomerView signIn(String rawCode) {
        String code = Customer.normalise(rawCode);
        boolean isNew = !customers.existsById(code);
        if (isNew) {
            customers.save(new Customer(code));
            log.info("New customer {}", code);
        }
        return new CustomerView(code, isNew, itemsOf(code));
    }

    /** Everything a known customer has, or empty if the code has never been used. */
    @Transactional(readOnly = true)
    public Optional<CustomerView> find(String rawCode) {
        String code = Customer.normalise(rawCode);
        if (!customers.existsById(code)) {
            return Optional.empty();
        }
        return Optional.of(new CustomerView(code, false, itemsOf(code)));
    }

    /** Whether a code is already in use — the login screen's typing hint. */
    @Transactional(readOnly = true)
    public boolean exists(String rawCode) {
        return customers.existsById(Customer.normalise(rawCode));
    }

    private List<CustomerItem> itemsOf(String code) {
        return applications.findByCustomerIdOrderByCreatedAtDesc(code, Limit.of(MAX_ITEMS))
                .stream()
                .map(this::toItem)
                .toList();
    }

    private CustomerItem toItem(Application app) {
        return new CustomerItem(
                app.getId(),
                kindOf(app),
                app.getProductCode(),
                app.getRequestedLimit(),
                app.getOverallStatus(),
                app.getCurrentStep(),
                app.isAwaitingSignature(),
                app.getPendingStep(),
                readOutputs(app.getOutputsJson()),
                app.getCreatedAt(),
                app.getUpdatedAt());
    }

    /**
     * Application or product.
     *
     * <p>Signing is what makes a card, so anything past the signature step is a product — but
     * <b>only if it is still alive or finished</b>. {@code currentStep} is never rewound: a
     * journey that dies at step 7 or 8 (the account service unreachable, a timeout, the card
     * bureau refusing) keeps {@code currentStep = 7} for ever. A plain "past step six" test would
     * call that a product and show somebody an account number that was never opened.</p>
     *
     * <p>So everything terminal-and-not-{@code COMPLETED} is an application, wherever it died.</p>
     */
    Kind kindOf(Application app) {
        boolean alive = Application.IN_PROGRESS.equals(app.getOverallStatus())
                || Application.COMPLETED.equals(app.getOverallStatus());
        return app.getCurrentStep() > signatureStep && alive ? Kind.PRODUCT : Kind.APPLICATION;
    }

    private Map<String, Object> readOutputs(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            // A customer's screen is not the place to fail over a malformed scratchpad. The
            // operator's own view of the same row is where that would be worth noticing.
            log.warn("Could not read outputs: {}", e.toString());
            return Map.of();
        }
    }
}
