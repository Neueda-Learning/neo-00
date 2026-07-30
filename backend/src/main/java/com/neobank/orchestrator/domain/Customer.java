package com.neobank.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Somebody who has used the customer surface: a four-character code they typed, and nothing else.
 *
 * <h2>This is identification, not authentication</h2>
 *
 * <p><b>Nothing is authorised by it.</b> There is no password, so "AB12 is already taken" and
 * "AB12 is yours" are the same fact and cannot be told apart — signing in is idempotent, and
 * anyone who types your code is you. The code decides which applications a customer's own screen
 * <em>lists</em>; every other endpoint ({@code /journey}, {@code /agreement}, {@code /sign},
 * {@code /support-case}) still keys on the application id alone and checks no ownership.</p>
 *
 * <p>That is correct for a single-user demonstration stack that has no auth anywhere, and it is
 * written here so nobody later mistakes the login screen for a security boundary and builds on
 * it as though it were one.</p>
 *
 * <h2>The code is the key</h2>
 *
 * <p>No surrogate id: there is nothing else about a customer to know, and a four-character code
 * a person can read out loud is a better key than a UUID they cannot. It is stored uppercased —
 * see {@link #normalise} for why that is not optional.</p>
 */
@Entity
@Table(name = "customer")
public class Customer {

    /**
     * Two letters then two digits. Deliberately small enough to say over a phone and to type
     * without a keyboard shortcut, which is the entire design brief.
     */
    private static final Pattern CODE = Pattern.compile("^[A-Z]{2}[0-9]{2}$");

    @Id
    @Column(length = 4)
    private String id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Customer() {
        // JPA
    }

    public Customer(String id) {
        this.id = id;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * The one place a customer code is cleaned up, and it must be used on <em>every</em> path that
     * accepts one.
     *
     * <p><b>Uppercasing is not cosmetic.</b> MySQL 8's default collation is case-insensitive and
     * H2's comparison is not, so {@code ab12} and {@code AB12} are one row on the real stack and
     * two rows in the tests. Normalising in one place makes the difference impossible to observe;
     * normalising on the login path but not on the create path would let an application point at
     * a customer row that does not exist.</p>
     *
     * @throws IllegalArgumentException if it is not two letters followed by two digits — which
     *         {@code ApiExceptionHandler} renders as a {@code 400} carrying this message
     */
    public static String normalise(String code) {
        String cleaned = code == null ? "" : code.trim().toUpperCase(java.util.Locale.ROOT);
        if (!CODE.matcher(cleaned).matches()) {
            throw new IllegalArgumentException(
                    "a customer code is two letters then two digits, like AB12 — got '" + code + "'");
        }
        return cleaned;
    }

    public String getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
