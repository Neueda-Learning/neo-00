package com.neobank.orchestrator.web;

import com.neobank.orchestrator.customer.CustomerDtos.CustomerView;
import com.neobank.orchestrator.customer.CustomerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signing in as a customer, and reading back what they have.
 *
 * <p><b>Not a security boundary</b> — see {@link com.neobank.orchestrator.domain.Customer}. The
 * code decides which applications get listed here; every other endpoint keys on the application
 * id alone and checks no ownership.</p>
 */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerService customers;

    public CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    /**
     * Sign in: creates the customer if the code is new, and either way returns everything they
     * have.
     *
     * <p>A {@code PUT} because the client chooses the id and the call is idempotent — sending it
     * twice leaves the same one customer. Deliberately a single call: a lookup followed by a
     * create would race, and there is no password, so there is no second caller to lose to.</p>
     *
     * <p>A malformed code raises {@code IllegalArgumentException} from
     * {@code Customer.normalise}, which the existing handler turns into a {@code 400} carrying
     * the sentence a person should read.</p>
     */
    @PutMapping("/{id}")
    public CustomerView signIn(@PathVariable("id") String id) {
        return customers.signIn(id);
    }

    /**
     * What a known customer has. {@code 404} if the code has never been used — which is also how
     * the login screen's typing hint tells "already in use" from "free".
     */
    @GetMapping("/{id}")
    public ResponseEntity<CustomerView> get(@PathVariable("id") String id) {
        return customers.find(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
