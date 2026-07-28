package com.neobank.orchestrator.saga;

import static java.util.Map.entry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * What each module's status word means to the journey.
 *
 * <p>The wire ships three words — {@code ACCEPTED · REJECTED · REFERRED} — but the module briefs
 * were written before the contract was simplified, so a team can reasonably arrive at any of three
 * vocabularies: the shipped three, the briefs' canonical set ({@code completed}, {@code rejected},
 * {@code application-manual}, {@code local-manual}, {@code in-progress}), or its own domain word
 * ({@code PASSED}, {@code CLEAR}, {@code SIGNED}, {@code ISSUED}…). Before this class existed the
 * orchestrator recognised only the first, and a module speaking either of the others was silently
 * ignored: the journey stalled and died on a 30-second timeout with nothing to say why. One
 * module's word choice took the whole journey down for all ten teams.</p>
 *
 * <p>Status semantics are orchestrator-owned, so the orchestrator learns all three. Every word
 * lands on one of four canonical values, and only those four appear anywhere downstream.</p>
 *
 * <h2>Why the domain words are keyed per module</h2>
 *
 * <p>{@code FAILED} does not mean the same thing twice. For {@link #canonical(String, String)
 * neo01} and neo03 it is a business answer — the applicant failed a rule — and the journey is
 * {@code REJECTED}. For neo07 and neo08 it is not a rejection at all: the core banking system or
 * the card bureau was unreachable, so the journey is {@code REFERRED} and a person retries. A
 * single global word&rarr;status table would silently reject applicants whose card bureau had a bad
 * minute. That pair is the entire reason this table is keyed by {@code serviceId}.</p>
 *
 * <h2>Why this is a table in code and not configuration</h2>
 *
 * <p>{@link ServiceRegistry} is {@code @ConfigurationProperties} because the journey <em>is</em>
 * configuration — which ten steps, in which order, at which URLs. This is the opposite case: the
 * table is derived from ten written briefs, and changing it changes what the saga <em>means</em>.
 * That belongs in a commit and a test, not in an environment variable. Configuration would also
 * put it in {@code infra/env/*.params}, where Spring's no-merge rule for collections means dev and
 * prod could quietly come to mean different things — a fault you cannot debug from a board.</p>
 *
 * <p>Rows are quoted from {@code project-requirements/v5/src/spec/module-NN-*} § <i>Status
 * mapping</i>, which is the source a team's brief is generated from.</p>
 */
public final class StatusVocabulary {

    /** The service is done and said yes: dispatch the next step, or complete the journey. */
    public static final String ACCEPTED = "ACCEPTED";
    /** A business no. The journey ends here; the remaining steps never run. */
    public static final String REJECTED = "REJECTED";
    /** A person must look at this. The journey ends here too, but nobody was refused. */
    public static final String REFERRED = "REFERRED";
    /** Still working. Recorded, but the journey neither advances nor ends. */
    public static final String IN_PROGRESS = "IN_PROGRESS";

    /**
     * Accepted from every module: the three the wire ships plus the briefs' canonical set.
     *
     * <p>{@code APPROVED} is here rather than only under neo02/neo05 because it is unambiguously
     * positive in every domain — no brief uses it to mean anything else.</p>
     */
    private static final Map<String, String> GLOBAL = Map.ofEntries(
            entry("ACCEPTED", ACCEPTED),
            entry("COMPLETED", ACCEPTED),           // the briefs' canonical "callback status" column
            entry("APPROVED", ACCEPTED),
            entry("REJECTED", REJECTED),
            entry("REFERRED", REFERRED),
            entry("APPLICATION_MANUAL", REFERRED),  // the briefs' "parks for a person"
            // The briefs' local-manual carries GO or STOP depending on the domain word in a
            // separate `outcome` field, which this wire does not have — so on its own it is
            // genuinely ambiguous. REFERRED is the knowingly-lossy safe read: a person was already
            // involved, so park it in front of one. Do NOT "fix" this to advance the journey;
            // half the local-manual rows in the briefs mean stop.
            entry("LOCAL_MANUAL", REFERRED),
            entry("IN_PROGRESS", IN_PROGRESS),
            entry("PENDING", IN_PROGRESS));         // neo06: PENDING -> in-progress, awaiting signature

    /** Each module's own word, from its brief's § Status mapping table. */
    private static final Map<String, Map<String, String>> BY_SERVICE = Map.ofEntries(
            // neo01 verification: PASSED -> completed · FAILED -> rejected · REVIEW -> application-manual.
            // FAILED is a business answer here — the applicant failed a rule. Contrast neo07/neo08.
            entry("neo01", Map.of("PASSED", ACCEPTED, "FAILED", REJECTED, "REVIEW", REFERRED)),
            // neo02 policy: all three words are already global. Listed anyway so every mapped module
            // reads the same way, and so a future divergence has somewhere obvious to go.
            entry("neo02", Map.of("APPROVED", ACCEPTED, "REJECTED", REJECTED, "REFERRED", REFERRED)),
            // neo03 kyc: a provider outage maps to REVIEW, never FAILED — so FAILED really is a
            // business answer about the document.
            entry("neo03", Map.of("VERIFIED", ACCEPTED, "FAILED", REJECTED, "REVIEW", REFERRED)),
            entry("neo04", Map.of("CLEAR", ACCEPTED, "HIT", REJECTED, "REVIEW", REFERRED)),
            entry("neo05", Map.of("APPROVED", ACCEPTED, "DECLINED", REJECTED, "REFERRED", REFERRED)),
            entry("neo06", Map.of("SIGNED", ACCEPTED, "DECLINED", REJECTED, "EXPIRED", REFERRED)),
            // neo07 account and neo08 card: FAILED is NEVER a rejection. The applicant did nothing
            // wrong — the core banking system or the card bureau was unreachable, so the journey
            // parks and an operator retries. This is why the table is keyed per module at all.
            entry("neo07", Map.of("OPENED", ACCEPTED, "FAILED", REFERRED)),
            entry("neo08", Map.of("ISSUED", ACCEPTED, "FAILED", REFERRED)),
            // neo09 support: both rows read completed — a support case can never break the journey.
            entry("neo09", Map.of("RESOLVED", ACCEPTED, "CLOSED", ACCEPTED)));
    // neo10 analytics has no row on purpose: its brief's status table is about snapshot states
    // (TAKEN / SERVED) and says no callback ever leaves the module. Global set only — TAKEN and
    // SERVED are not callback statuses and must not be added here.

    private StatusVocabulary() {
    }

    /**
     * What {@code word} means coming from {@code serviceId}, or empty if this orchestrator has
     * never been told.
     *
     * <p>The module's own table is consulted first, so its word always wins. Nothing global
     * contradicts a module today and {@code noModulesOwnWordContradictsTheGlobalTable} pins that;
     * if one ever does, the module that said it decides what it meant.</p>
     *
     * <p>An unknown or null {@code serviceId} gets the global table only. A typo'd id must not
     * silently borrow another module's vocabulary — and it already fails the current-step guard in
     * {@link SagaStore} with a warning of its own.</p>
     */
    public static Optional<String> canonical(String serviceId, String word) {
        String key = normalize(word);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(own(serviceId).getOrDefault(key, GLOBAL.get(key)));
    }

    /**
     * Every word this module may send, sorted — the list the unknown-word warning prints, so a
     * team reads what would have worked instead of guessing.
     */
    public static List<String> acceptedWords(String serviceId) {
        Set<String> words = new TreeSet<>(GLOBAL.keySet());
        words.addAll(own(serviceId).keySet());
        return new ArrayList<>(words);
    }

    /**
     * This module's own table, or an empty one. {@code Map.ofEntries} throws on a null key rather
     * than missing, so the null check is load-bearing: a status update can carry any
     * {@code serviceId} a module chooses to send, including none.
     */
    private static Map<String, String> own(String serviceId) {
        return serviceId == null ? Map.of() : BY_SERVICE.getOrDefault(serviceId, Map.of());
    }

    /**
     * Trim, upper-case, and treat {@code -}, {@code _} and a space as the same separator, so
     * {@code application-manual}, {@code APPLICATION_MANUAL} and {@code Application Manual} are one
     * word. {@code Locale.ROOT} because under a Turkish default locale {@code in-progress}
     * upper-cases to {@code İN_PROGRESS} and would match nothing.
     */
    public static String normalize(String word) {
        return word == null ? "" : word.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
}
