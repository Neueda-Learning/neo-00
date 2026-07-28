package com.neobank.orchestrator;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

/**
 * Boots the orchestrator against H2 and drives its real HTTP surface — the endpoints the
 * two front-end screens read.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// Its own in-memory database, so applications created here can't be swept or renumbered
// by another test class sharing the default H2 URL.
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:orchestratorweb;MODE=MySQL;DB_CLOSE_DELAY=-1")
class OrchestratorApplicationTests {

    @MockBean(answer = Answers.RETURNS_DEEP_STUBS)
    RestClient restClient;   // no services listening in a test; dispatch is stubbed

    @Autowired
    MockMvc mvc;

    @Test
    void contextLoads() {
        // Fails if wiring, the Liquibase changelog or ddl-auto=validate is broken.
    }

    @Test
    void healthReportsUp() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database.status").value("UP"));
    }

    @Test
    void infoReportsTheEightStepSequence() throws Exception {
        mvc.perform(get("/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps").value(8))
                .andExpect(jsonPath("$.sequence", hasSize(8)))
                .andExpect(jsonPath("$.sequence[0].serviceId").value("neo01"))
                .andExpect(jsonPath("$.sequence[7].serviceId").value("neo08"));
    }

    @Test
    void theTwoAnalyticalModulesAreNotInTheJourney() throws Exception {
        // neo09 and neo10 observe the journey rather than sit in it, so the orchestrator
        // never dispatches to them and they are absent from every sequence view. They still
        // deploy and serve their own UIs — this asserts only that the saga ignores them.
        mvc.perform(get("/info"))
                .andExpect(jsonPath("$.sequence[*].serviceId")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItems("neo09", "neo10"))));
    }

    @Test
    void servicesScreenListsAllEightBoxesEvenBeforeAnyTraffic() throws Exception {
        mvc.perform(get("/api/v1/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(8)))
                .andExpect(jsonPath("$[0].serviceId").value("neo01"))
                .andExpect(jsonPath("$[0].step").value(1));
    }

    @Test
    void creatingAnApplicationReturnsItWithTenSteps() throws Exception {
        String body = mvc.perform(post("/api/v1/applications"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.overallStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.applicantName").exists())
                .andExpect(jsonPath("$.events[0].eventType").value("JOURNEY_STARTED"))
                .andReturn().getResponse().getContentAsString();

        String id = body.replaceAll("(?s).*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        String applicantName =
                body.replaceAll("(?s).*\"applicantName\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        // The bare resource is the api-contract §4 application object — the same one the ten
        // services are handed in their dispatch envelope. Round-tripped through the database
        // here, which the mocked @WebMvcTest slice cannot prove.
        mvc.perform(get("/api/v1/applications/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value(id))
                .andExpect(jsonPath("$.applicant.fullName").value(applicantName))
                .andExpect(jsonPath("$.product.productCode").exists());

        // The journey view keeps the board row and the append-only log.
        mvc.perform(get("/api/v1/applications/" + id + "/journey"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.application.applicant.fullName").value(applicantName))
                .andExpect(jsonPath("$.events[0].eventType").value("JOURNEY_STARTED"));

        // Name search: substring, case-insensitive, and it answers in §4 objects.
        String fragment = applicantName.substring(applicantName.length() - 4).toUpperCase();
        mvc.perform(get("/api/v1/applications").param("name", fragment))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.applicationId=='" + id + "')]", hasSize(1)));

        mvc.perform(get("/api/v1/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + id + "')]", hasSize(1)))
                // steps[*] flattens the matched row's eight StepViews; steps[0] would give
                // the first one, not the array.
                .andExpect(jsonPath("$[?(@.id=='" + id + "')].steps[*]", hasSize(8)))
                .andExpect(jsonPath("$[?(@.id=='" + id + "')].steps[*].serviceId")
                        .value(org.hamcrest.Matchers.hasItems("neo01", "neo08")));
    }

    @Test
    void unknownApplicationIs404() throws Exception {
        mvc.perform(get("/api/v1/applications/APP-NOPE"))
                .andExpect(status().isNotFound());
    }

    @Test
    void generatorTogglesOnAndOff() throws Exception {
        mvc.perform(get("/api/v1/generator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));   // off at boot, always

        mvc.perform(post("/api/v1/generator")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"intervalMs\":4000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.intervalMs").value(4000));

        mvc.perform(post("/api/v1/generator")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.intervalMs").value(4000));   // interval survives a stop
    }

    @Test
    void generatorIntervalHasAFloor() throws Exception {
        mvc.perform(post("/api/v1/generator")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intervalMs\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intervalMs").value(500));
    }

    @Test
    void eventsEndpointAnswersWithAndWithoutAServiceFilter() throws Exception {
        mvc.perform(post("/api/v1/applications")).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").exists());

        mvc.perform(get("/api/v1/events").param("serviceId", "neo09"))
                .andExpect(status().isOk());
    }
}
