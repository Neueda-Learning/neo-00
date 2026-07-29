package com.neobank.orchestrator.domain;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Customers by their code. There is nothing to search: the code is the key, and a customer who
 * cannot remember it has no other way in — which is the honest consequence of a login with no
 * password and no recovery.
 */
public interface CustomerRepository extends JpaRepository<Customer, String> {
}
