package com.neobank.orchestrator.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The frozen {@code neobank-sidecar@v1} scenario corpus.
 *
 * <p>This is the sidecar's named-file loader with its external directory overlay deliberately
 * removed. The index remains authoritative, which keeps exploded-classpath and fat-jar behaviour
 * identical.</p>
 */
@Component
public class ScenarioLibrary {

    static final String ROOT = "scenarios/";
    static final String INDEX = ROOT + "index.json";

    private static final Logger log = LoggerFactory.getLogger(ScenarioLibrary.class);
    private static final Pattern DATE_TOKEN =
            Pattern.compile("\\{\\{today(?:-(\\d+)y)?(?:([+-])(\\d+)d)?}}");

    private final ObjectMapper json;
    private final Map<String, Object> catalogue;

    public ScenarioLibrary(ObjectMapper json) {
        this.json = json;
        this.catalogue = load();
    }

    /** The whole catalogue: index metadata, with each scenario's request attached. */
    public Map<String, Object> catalogue() {
        return catalogue;
    }

    /** One scenario's envelope by scenario id (for example {@code SIM-01}). */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> envelopeOf(String scenarioId) {
        return scenarios().stream()
                .filter(scenario -> scenarioId.equals(String.valueOf(scenario.get("id"))))
                .map(scenario -> (Map<String, Object>) scenario.get("request"))
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> scenarios() {
        return (List<Map<String, Object>>) catalogue.getOrDefault("scenarios", List.of());
    }

    /** Substitute the sidecar's relative date tokens before JSON parsing. */
    static String resolveDates(String raw, LocalDate today) {
        Matcher matcher = DATE_TOKEN.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            LocalDate date = matcher.group(1) == null
                    ? today
                    : today.minusYears(Long.parseLong(matcher.group(1)));
            if (matcher.group(2) != null) {
                long days = Long.parseLong(matcher.group(3));
                date = "+".equals(matcher.group(2)) ? date.plusDays(days) : date.minusDays(days);
            }
            matcher.appendReplacement(out, date.toString());
        }
        matcher.appendTail(out);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> load() {
        LocalDate today = LocalDate.now();
        Map<String, Object> index;
        try {
            index = json.readValue(resolveDates(readClasspath(INDEX), today), Map.class);
        } catch (Exception e) {
            log.error("Scenario index {} could not be read: {}", INDEX, e.toString());
            return Map.of("scenarios", List.of(), "count", 0,
                    "error", "cannot read " + INDEX + ": " + e);
        }

        List<Map<String, Object>> entries =
                (List<Map<String, Object>>) index.getOrDefault("scenarios", List.of());
        List<Map<String, Object>> loaded = new ArrayList<>(entries.size());
        for (Map<String, Object> entry : entries) {
            Map<String, Object> scenario = new LinkedHashMap<>(entry);
            String file = String.valueOf(entry.get("file"));
            try {
                String raw = readClasspath(ROOT + file);
                scenario.put("request", json.readValue(resolveDates(raw, today), Map.class));
            } catch (Exception e) {
                log.warn("Scenario {} could not be read: {}", file, e.toString());
                scenario.put("request", null);
                scenario.put("error", e.toString());
            }
            loaded.add(Collections.unmodifiableMap(scenario));
        }

        Map<String, Object> result = new LinkedHashMap<>(index);
        result.put("scenarios", List.copyOf(loaded));
        result.put("count", loaded.size());
        log.info("Scenario library loaded: {} applications", loaded.size());
        return Collections.unmodifiableMap(result);
    }

    private static String readClasspath(String path) throws IOException {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
