package com.neobank.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/** CI guard: the packaged fat jar must carry the same 27 named files as the source tree. */
class ScenarioCorpusJarIT {

    @Test
    void packagedJarContainsTheWholeScenarioCorpus() throws Exception {
        Path jar = Files.list(Path.of("target"))
                .filter(path -> path.getFileName().toString().matches(
                        "neobank-orchestrator-.*\\.jar"))
                .filter(path -> !path.getFileName().toString().endsWith(".original"))
                .findFirst()
                .orElseThrow();

        try (JarFile archive = new JarFile(jar.toFile())) {
            long count = archive.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> entry.getName().startsWith("BOOT-INF/classes/scenarios/"))
                    .count();
            assertThat(count).isEqualTo(27);
        }
    }
}
