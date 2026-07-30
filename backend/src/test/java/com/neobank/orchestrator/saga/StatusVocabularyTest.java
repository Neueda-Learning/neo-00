package com.neobank.orchestrator.saga;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The whole vocabulary, exercised here because a journey cannot exercise it.
 *
 * <p>A journey only reaches the steps it gets to, and only with the outcome the seeded applicant
 * happens to produce — so driving the saga proves one row of the table at a time, by luck. Every
 * word every module may send is asserted here instead. {@link SagaFlowTest} then proves the two
 * cases where the mapping changes what the state machine does.</p>
 */
class StatusVocabularyTest {

    private static final List<String> MODULES = List.of(
            "neo01", "neo02", "neo03", "neo04", "neo05",
            "neo06", "neo07", "neo08", "neo09", "neo10");

    // ---- the three vocabularies ----

    @Test
    void theShippedThreeWordsAreAcceptedFromEveryModule() {
        for (String serviceId : MODULES) {
            assertThat(StatusVocabulary.canonical(serviceId, "ACCEPTED"))
                    .as("ACCEPTED from %s", serviceId).contains(StatusVocabulary.ACCEPTED);
            assertThat(StatusVocabulary.canonical(serviceId, "REJECTED"))
                    .as("REJECTED from %s", serviceId).contains(StatusVocabulary.REJECTED);
            assertThat(StatusVocabulary.canonical(serviceId, "REFERRED"))
                    .as("REFERRED from %s", serviceId).contains(StatusVocabulary.REFERRED);
        }
    }

    @Test
    void theBriefsCanonicalWordsAreAcceptedFromEveryModule() {
        for (String serviceId : MODULES) {
            assertThat(StatusVocabulary.canonical(serviceId, "completed"))
                    .as("completed from %s", serviceId).contains(StatusVocabulary.ACCEPTED);
            assertThat(StatusVocabulary.canonical(serviceId, "application-manual"))
                    .as("application-manual from %s", serviceId).contains(StatusVocabulary.REFERRED);
            assertThat(StatusVocabulary.canonical(serviceId, "local-manual"))
                    .as("local-manual from %s", serviceId).contains(StatusVocabulary.REFERRED);
            assertThat(StatusVocabulary.canonical(serviceId, "in-progress"))
                    .as("in-progress from %s", serviceId).contains(StatusVocabulary.IN_PROGRESS);
        }
    }

    @Test
    void eachModulesOwnDomainWordsMapToTheOutcomeItsBriefGives() {
        assertThat(StatusVocabulary.canonical("neo01", "PASSED")).contains(StatusVocabulary.ACCEPTED);
        assertThat(StatusVocabulary.canonical("neo01", "REVIEW")).contains(StatusVocabulary.REFERRED);

        assertThat(StatusVocabulary.canonical("neo02", "APPROVED")).contains(StatusVocabulary.ACCEPTED);

        assertThat(StatusVocabulary.canonical("neo03", "VERIFIED")).contains(StatusVocabulary.ACCEPTED);
        assertThat(StatusVocabulary.canonical("neo03", "REVIEW")).contains(StatusVocabulary.REFERRED);

        assertThat(StatusVocabulary.canonical("neo04", "CLEAR")).contains(StatusVocabulary.ACCEPTED);
        assertThat(StatusVocabulary.canonical("neo04", "HIT")).contains(StatusVocabulary.REJECTED);
        assertThat(StatusVocabulary.canonical("neo04", "REVIEW")).contains(StatusVocabulary.REFERRED);

        assertThat(StatusVocabulary.canonical("neo05", "APPROVED")).contains(StatusVocabulary.ACCEPTED);
        assertThat(StatusVocabulary.canonical("neo05", "DECLINED")).contains(StatusVocabulary.REJECTED);

        assertThat(StatusVocabulary.canonical("neo06", "SIGNED")).contains(StatusVocabulary.ACCEPTED);
        assertThat(StatusVocabulary.canonical("neo06", "DECLINED")).contains(StatusVocabulary.REJECTED);
        assertThat(StatusVocabulary.canonical("neo06", "EXPIRED")).contains(StatusVocabulary.REFERRED);
        // The one non-terminal row in any brief: PENDING while the customer has not signed yet.
        assertThat(StatusVocabulary.canonical("neo06", "PENDING")).contains(StatusVocabulary.IN_PROGRESS);

        assertThat(StatusVocabulary.canonical("neo07", "OPENED")).contains(StatusVocabulary.ACCEPTED);
        assertThat(StatusVocabulary.canonical("neo08", "ISSUED")).contains(StatusVocabulary.ACCEPTED);

        // Both of neo09's rows read completed — a support case can never break the journey.
        assertThat(StatusVocabulary.canonical("neo09", "RESOLVED")).contains(StatusVocabulary.ACCEPTED);
        assertThat(StatusVocabulary.canonical("neo09", "CLOSED")).contains(StatusVocabulary.ACCEPTED);
    }

    @Test
    void failedIsARejectionFromTheModulesThatJudgeTheApplicantAndAReferralFromTheOnesThatCallAnUnreachableSystem() {
        // neo01 and neo03 decide about the applicant: FAILED means a rule said no.
        assertThat(StatusVocabulary.canonical("neo01", "FAILED")).contains(StatusVocabulary.REJECTED);
        assertThat(StatusVocabulary.canonical("neo03", "FAILED")).contains(StatusVocabulary.REJECTED);

        // neo07 and neo08 call somebody else: FAILED means the core banking system or the card
        // bureau was unreachable. Nobody was refused, so a person retries.
        assertThat(StatusVocabulary.canonical("neo07", "FAILED")).contains(StatusVocabulary.REFERRED);
        assertThat(StatusVocabulary.canonical("neo08", "FAILED")).contains(StatusVocabulary.REFERRED);
    }

    // ---- shape of the input ----

    @Test
    void aWordIsRecognisedWhateverItsCaseSpacingOrSeparator() {
        assertThat(StatusVocabulary.canonical("neo01", "passed")).contains(StatusVocabulary.ACCEPTED);
        assertThat(StatusVocabulary.canonical("neo01", "  PASSED  ")).contains(StatusVocabulary.ACCEPTED);
        assertThat(StatusVocabulary.canonical("neo02", "application-manual")).contains(StatusVocabulary.REFERRED);
        assertThat(StatusVocabulary.canonical("neo02", "APPLICATION_MANUAL")).contains(StatusVocabulary.REFERRED);
        assertThat(StatusVocabulary.canonical("neo02", "Application Manual")).contains(StatusVocabulary.REFERRED);
        assertThat(StatusVocabulary.canonical("neo06", "In-Progress")).contains(StatusVocabulary.IN_PROGRESS);
    }

    // ---- the table's own invariants ----

    @Test
    void noModulesOwnWordContradictsTheGlobalTable() {
        // Lookup is module-first, and today nothing global disagrees with a module. This says so
        // out loud: the day someone adds FAILED to the global table, neo07's "the bureau was down"
        // reads as "the applicant was refused" to anyone consulting the global one, and this fails.
        for (String serviceId : MODULES) {
            for (String word : StatusVocabulary.acceptedWords(serviceId)) {
                Optional<String> global = StatusVocabulary.canonical(null, word);
                if (global.isPresent()) {
                    assertThat(StatusVocabulary.canonical(serviceId, word))
                            .as("'%s' means one thing globally and another from %s", word, serviceId)
                            .isEqualTo(global);
                }
            }
        }
    }

    @Test
    void everyMappingLandsOnOneOfTheFourWordsTheSagaKnowsHowToActOn() {
        // What makes SagaStore's default arm unreachable. A fifth canonical value would fall
        // through it and silently stall a journey — the exact fault this class exists to remove.
        for (String serviceId : MODULES) {
            for (String word : StatusVocabulary.acceptedWords(serviceId)) {
                assertThat(StatusVocabulary.canonical(serviceId, word).orElseThrow())
                        .as("'%s' from %s", word, serviceId)
                        .isIn(StatusVocabulary.ACCEPTED, StatusVocabulary.REJECTED,
                                StatusVocabulary.REFERRED, StatusVocabulary.IN_PROGRESS);
            }
        }
    }

    // ---- what it refuses to do ----

    @Test
    void aWordThisOrchestratorHasNotBeenTaughtReturnsEmptyRatherThanAGuess() {
        assertThat(StatusVocabulary.canonical("neo01", "MAYBE")).isEmpty();
        assertThat(StatusVocabulary.canonical("neo01", "")).isEmpty();
        assertThat(StatusVocabulary.canonical("neo01", null)).isEmpty();
        // FAILED is in four modules' tables but not neo02's, and not the global one. The
        // vocabulary refuses to borrow a neighbour's word — it would be a guess about what the
        // policy module meant, and guessing wrong here rejects a real applicant.
        assertThat(StatusVocabulary.canonical("neo02", "FAILED")).isEmpty();
    }

    @Test
    void rawAndCanonicalWordsRemainDistinctForTheSimulator() {
        String raw = "CLEAR";
        assertThat(StatusVocabulary.canonical("neo04", raw))
                .contains(StatusVocabulary.ACCEPTED);
        assertThat(raw).isEqualTo("CLEAR");

        assertThat(StatusVocabulary.canonical("neo04", "mystery")).isEmpty();
        assertThat(StatusVocabulary.acceptedWords("neo04")).contains("CLEAR");
    }

    @Test
    void aModuleWithNoTableOfItsOwnStillGetsTheGlobalWords() {
        assertThat(StatusVocabulary.canonical("neo10", "COMPLETED")).contains(StatusVocabulary.ACCEPTED);
        assertThat(StatusVocabulary.canonical("neo10", "PASSED")).isEmpty();

        assertThat(StatusVocabulary.canonical("neo99", "ACCEPTED")).contains(StatusVocabulary.ACCEPTED);
        assertThat(StatusVocabulary.canonical(null, "ACCEPTED")).contains(StatusVocabulary.ACCEPTED);
    }

    @Test
    void theWordsAModuleMaySendAreListedSoTheWarningTellsAnOperatorWhatToFix() {
        List<String> neo07 = StatusVocabulary.acceptedWords("neo07");
        assertThat(neo07).contains("OPENED", "FAILED", "ACCEPTED", "COMPLETED");
        assertThat(neo07).doesNotContain("PASSED", "CLEAR");
        assertThat(neo07).isSorted();

        // A module with no table of its own still has something to print.
        assertThat(StatusVocabulary.acceptedWords("neo10")).contains("ACCEPTED", "REJECTED", "REFERRED");
    }
}
