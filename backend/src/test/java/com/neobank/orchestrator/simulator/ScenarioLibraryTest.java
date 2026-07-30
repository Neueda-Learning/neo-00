package com.neobank.orchestrator.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Ported from {@code neobank-sidecar@v1}; additions pin the two deliberate product-code drift
 * fixes made while importing the otherwise exact v1 corpus.
 */
class ScenarioLibraryTest {

    private static Map<String, Object> catalogue;
    private static List<Map<String, Object>> scenarios;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadOnce() {
        catalogue = new ScenarioLibrary(new ObjectMapper()).catalogue();
        scenarios = (List<Map<String, Object>>) catalogue.get("scenarios");
    }

    @Test
    void loadsEveryScenarioInTheIndex() {
        assertThat(scenarios).hasSize(26);
        assertThat(catalogue.get("count")).isEqualTo(scenarios.size());
        assertThat(scenarios.stream()
                .filter(scenario -> scenario.get("error") != null)
                .map(scenario -> scenario.get("file") + ": " + scenario.get("error")))
                .as("scenarios that failed to parse")
                .isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyScenarioIsASendableEnvelope() {
        for (Map<String, Object> scenario : scenarios) {
            String id = String.valueOf(scenario.get("id"));
            Map<String, Object> request = (Map<String, Object>) scenario.get("request");

            assertThat(request).as("%s has a request", id).isNotNull();
            assertThat(request.get("command")).as("%s command", id).isNotNull();
            assertThat(request.get("application")).as("%s application", id).isNotNull();
            assertThat(scenario.get("title")).as("%s title", id).isNotNull();
            assertThat(scenario.get("trait")).as("%s trait", id).isNotNull();

            Integer expectHttp = (Integer) scenario.get("expectHttp");
            if (expectHttp != null && expectHttp == 202) {
                assertThat(request.get("applicationId"))
                        .as("%s applicationId", id)
                        .isEqualTo(scenario.get("applicationId"));
                assertThat(request.get("applicationId")).as("%s applicationId", id).isNotNull();
            }
        }
    }

    @Test
    void scenarioIdsAndFilenamesAreUnique() {
        assertThat(scenarios.stream().map(s -> s.get("id")).collect(Collectors.toSet()))
                .hasSize(scenarios.size());
        assertThat(scenarios.stream().map(s -> s.get("file")).collect(Collectors.toSet()))
                .hasSize(scenarios.size());
    }

    @Test
    void reasonCodesUseTheLockedRegistryPrefixes() {
        Set<String> prefixes = new HashSet<>(Arrays.asList(
                "VER_", "POL_", "KYC_", "SCR_", "CRE_", "AGR_", "ACC_", "CRD_"));
        for (Map<String, Object> scenario : scenarios) {
            @SuppressWarnings("unchecked")
            List<String> codes = (List<String>) scenario.getOrDefault("reasonCodes", List.of());
            for (String code : codes) {
                assertThat(prefixes.stream().anyMatch(code::startsWith))
                        .as("%s reason code %s", scenario.get("id"), code)
                        .isTrue();
            }
        }
    }

    @Test
    void noScenarioFileIsMissingFromTheIndex() throws Exception {
        Resource[] onDisk = new PathMatchingResourcePatternResolver()
                .getResources("classpath:" + ScenarioLibrary.ROOT + "*.json");

        List<String> files = new ArrayList<>();
        for (Resource resource : onDisk) {
            String name = resource.getFilename();
            if (name != null && !name.equals("index.json")) {
                files.add(name);
            }
        }
        Set<Object> indexed = scenarios.stream().map(s -> s.get("file")).collect(Collectors.toSet());

        assertThat(files)
                .as("every .json in %s must have a row in index.json", ScenarioLibrary.ROOT)
                .allMatch(indexed::contains);
        assertThat(files).hasSize(scenarios.size());
    }

    @Test
    void resolvesRelativeDateTokens() {
        LocalDate today = LocalDate.of(2026, 7, 26);

        assertThat(ScenarioLibrary.resolveDates("{{today}}", today)).isEqualTo("2026-07-26");
        assertThat(ScenarioLibrary.resolveDates("{{today-18y}}", today)).isEqualTo("2008-07-26");
        assertThat(ScenarioLibrary.resolveDates("{{today-18y+1d}}", today))
                .isEqualTo("2008-07-27");
        assertThat(ScenarioLibrary.resolveDates("{{today-18y-1d}}", today))
                .isEqualTo("2008-07-25");
        assertThat(ScenarioLibrary.resolveDates(
                "\"{{today}}T09:14:00Z\" {{today-1y}}", today))
                .isEqualTo("\"2026-07-26T09:14:00Z\" 2025-07-26");
        assertThat(ScenarioLibrary.resolveDates("2025-11-30", today))
                .isEqualTo("2025-11-30");
    }

    @Test
    void theAgeBoundaryScenariosCannotAgeOut() {
        LocalDate today = LocalDate.now();
        LocalDate exactly18 = LocalDate.parse(dateOfBirth("SIM-03"));
        LocalDate oneDayShort = LocalDate.parse(dateOfBirth("SIM-04"));

        assertThat(ChronoUnit.YEARS.between(exactly18, today)).isEqualTo(18);
        assertThat(ChronoUnit.YEARS.between(oneDayShort, today)).isEqualTo(17);
        assertThat(oneDayShort).isEqualTo(exactly18.plusDays(1));
    }

    @Test
    void importedAgeScenariosUseARealCatalogueProduct() {
        assertThat(productCode("SIM-03")).isEqualTo("CREDIT_CARD_STANDARD");
        assertThat(productCode("SIM-04")).isEqualTo("CREDIT_CARD_STANDARD");
        assertThat(productCode("SIM-24")).isEqualTo("CREDIT_CARD_PREMIUM");
    }

    @SuppressWarnings("unchecked")
    private static String dateOfBirth(String scenarioId) {
        Map<String, Object> request = request(scenarioId);
        Map<String, Object> application = (Map<String, Object>) request.get("application");
        Map<String, Object> applicant = (Map<String, Object>) application.get("applicant");
        return String.valueOf(applicant.get("dateOfBirth"));
    }

    @SuppressWarnings("unchecked")
    private static String productCode(String scenarioId) {
        Map<String, Object> application =
                (Map<String, Object>) request(scenarioId).get("application");
        Map<String, Object> product = (Map<String, Object>) application.get("product");
        return String.valueOf(product.get("productCode"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> request(String scenarioId) {
        return scenarios.stream()
                .filter(scenario -> scenarioId.equals(scenario.get("id")))
                .map(scenario -> (Map<String, Object>) scenario.get("request"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no scenario " + scenarioId));
    }
}
