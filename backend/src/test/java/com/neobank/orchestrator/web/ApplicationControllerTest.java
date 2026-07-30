package com.neobank.orchestrator.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neobank.orchestrator.customer.CustomerService;
import com.neobank.orchestrator.domain.Application;
import com.neobank.orchestrator.generator.GeneratorService;
import com.neobank.orchestrator.saga.SagaDtos.ApplicationDetail;
import com.neobank.orchestrator.saga.SagaDtos.ApplicationStatusUpdate;
import com.neobank.orchestrator.saga.SagaEngine;
import com.neobank.orchestrator.saga.SagaStore;
import com.neobank.orchestrator.simulator.SimulationService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The application resource. Create it two ways — a customer-filled body (the customer journey) and
 * no body (the backoffice "+ one" fixture path) — the guard that rejects a malformed submission,
 * and the {@code PUT} the ten services report their answers on.
 *
 * <p>The {@code PUT} cases matter most: that endpoint is what drives every journey, and the sidecar
 * ships a copy of it. If its shape changes here and not there, teams develop against a contract the
 * real orchestrator does not honour.</p>
 */
@WebMvcTest(ApplicationController.class)
class ApplicationControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    SagaStore store;

    @MockBean
    GeneratorService generator;

    @MockBean
    SagaEngine engine;

    @MockBean
    CustomerService customers;

    @MockBean
    SimulationService simulations;

    private void stubDetail(String id, String applicant, String product) {
        when(store.detail(id)).thenReturn(Optional.of(
                new ApplicationDetail(id, applicant, product, 8000, "WEB", 0, null, false,
                        Application.IN_PROGRESS, Map.of(), Map.of(), null, null,
                        List.of(), List.of())));
    }

    @Test
    void aSubmittedApplicationIsCreatedFromTheBody() throws Exception {
        Application app = new Application("APP-0007", "corr", "Ada Byron",
                "CREDIT_CARD_REWARDS", 8000, "WEB", "{}");
        when(generator.createAndStart(anyMap(), any())).thenReturn(app);
        stubDetail("APP-0007", "Ada Byron", "CREDIT_CARD_REWARDS");

        mvc.perform(post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channel":"WEB",
                                 "applicant":{"fullName":"Ada Byron"},
                                 "product":{"productCode":"CREDIT_CARD_REWARDS",
                                            "requestedCreditLimit":8000}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("APP-0007"))
                .andExpect(jsonPath("$.productCode").value("CREDIT_CARD_REWARDS"));

        verify(generator).createAndStart(anyMap(), any());
    }

    /**
     * Who applied rides as a query parameter and reaches the row, never the body. The body is the
     * api-contract §4 object that ten modules bind into typed records; a key they have never seen
     * is not something this orchestrator can verify from here.
     */
    @Test
    void aCustomerCodeOnTheQueryStringIsPassedThroughToTheApplication() throws Exception {
        Application app = new Application("APP-0009", "corr", "Ada Byron",
                "CREDIT_CARD_REWARDS", 8000, "WEB", "{}", "AB12");
        when(customers.exists("AB12")).thenReturn(true);
        when(generator.createAndStart(anyMap(), eq("AB12"))).thenReturn(app);
        stubDetail("APP-0009", "Ada Byron", "CREDIT_CARD_REWARDS");

        mvc.perform(post("/api/v1/applications?customerId=ab12")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"applicant":{"fullName":"Ada Byron"},
                                 "product":{"productCode":"CREDIT_CARD_REWARDS"}}
                                """))
                .andExpect(status().isCreated());

        // Uppercased on the way in — MySQL's collation is case-insensitive and H2's is not.
        verify(generator).createAndStart(anyMap(), eq("AB12"));
    }

    @Test
    void applyingAsACodeNobodySignedInWithIsRefused() throws Exception {
        when(customers.exists("ZZ99")).thenReturn(false);

        mvc.perform(post("/api/v1/applications?customerId=ZZ99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"applicant":{"fullName":"Ada Byron"},
                                 "product":{"productCode":"CREDIT_CARD_REWARDS"}}
                                """))
                .andExpect(status().isNotFound());

        verify(generator, never()).createAndStart(anyMap(), any());
    }

    @Test
    void noBodyStillGeneratesAFixture() throws Exception {
        Application app = new Application("APP-0001", "corr", "Maria Nowak",
                "CREDIT_CARD_STANDARD", 3000, "MOBILE_APP", "{}");
        when(generator.createAndStart(any())).thenReturn(app);
        stubDetail("APP-0001", "Maria Nowak", "CREDIT_CARD_STANDARD");

        mvc.perform(post("/api/v1/applications"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("APP-0001"));

        verify(generator).createAndStart(any());
    }

    @Test
    void aSubmissionMissingTheApplicantIsRejected() throws Exception {
        mvc.perform(post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":{\"productCode\":\"CREDIT_CARD_REWARDS\"}}"))
                .andExpect(status().isBadRequest());
    }

    // ---- PUT /{id}: where the ten services report their answers -------------------------

    @Test
    void aServiceReportsItsAnswerWithAPutOnTheApplication() throws Exception {
        mvc.perform(put("/api/v1/applications/{id}", "APP-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serviceId":"neo01","status":"ACCEPTED","comment":"all good"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true))
                .andExpect(jsonPath("$.applicationId").value("APP-0001"));

        // The id comes off the path; the body carries only the three fields.
        verify(engine).handleApplicationStatusUpdate("APP-0001",
                new ApplicationStatusUpdate("neo01", "ACCEPTED", "all good"));
    }

    @Test
    void anUnknownApplicationStillAnswers200() throws Exception {
        // A service must not be left retrying because its late or misdirected report was refused.
        // SagaStore decides whether it matters; the endpoint always accepts it.
        mvc.perform(put("/api/v1/applications/{id}", "APP-NOPE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceId\":\"neo01\",\"status\":\"ACCEPTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));

        verify(engine).handleApplicationStatusUpdate(eq("APP-NOPE"), any(ApplicationStatusUpdate.class));
    }

    @Test
    void aReportMissingItsStatusIsRejectedBeforeTheEngineSeesIt() throws Exception {
        mvc.perform(put("/api/v1/applications/{id}", "APP-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceId\":\"neo01\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(engine);
    }

    // ---- GET /{id}: the application a service reads back ---------------------------------

    /** The §4 application object, nested fields and all — not this orchestrator's board row. */
    private static Map<String, Object> application(String id, String fullName) {
        return Map.of(
                "applicationId", id,
                "channel", "WEB",
                "applicant", Map.of("fullName", fullName, "dateOfBirth", "1996-04-11"),
                "product", Map.of("productCode", "CREDIT_CARD_REWARDS",
                        "requestedCreditLimit", 8000));
    }

    /**
     * The shape four teams' modules deserialize into. If this ever goes back to returning the
     * board row, {@code applicant} disappears and they all silently read nulls — the two shapes
     * overlap only on {@code channel}, so nothing throws.
     */
    @Test
    void getReturnsTheApplicationObjectAServiceExpects() throws Exception {
        when(store.application("APP-0001"))
                .thenReturn(Optional.of(application("APP-0001", "Maria Nowak")));

        mvc.perform(get("/api/v1/applications/{id}", "APP-0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value("APP-0001"))
                .andExpect(jsonPath("$.applicant.fullName").value("Maria Nowak"))
                .andExpect(jsonPath("$.product.productCode").value("CREDIT_CARD_REWARDS"))
                // The board row's fields must NOT be here — this is the application, not the view.
                .andExpect(jsonPath("$.overallStatus").doesNotExist())
                .andExpect(jsonPath("$.events").doesNotExist());
    }

    /** Services map this onto their own "no such application" error, so it has to stay a 404. */
    @Test
    void anUnknownApplicationIs404NotAnEmptyObject() throws Exception {
        when(store.application("APP-NOPE")).thenReturn(Optional.empty());
        when(simulations.application("APP-NOPE")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/applications/{id}", "APP-NOPE"))
                .andExpect(status().isNotFound());
    }

    @Test
    void anIdMissFallsThroughToTheSimulator() throws Exception {
        when(store.application("SIM-01-neo05-3")).thenReturn(Optional.empty());
        when(simulations.application("SIM-01-neo05-3"))
                .thenReturn(Optional.of(application("SIM-01-neo05-3", "Maria Nowak")));

        mvc.perform(get("/api/v1/applications/{id}", "SIM-01-neo05-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value("SIM-01-neo05-3"));
    }

    @Test
    void theJourneyViewKeepsTheBoardRowAndTheEventLog() throws Exception {
        stubDetail("APP-0001", "Maria Nowak", "CREDIT_CARD_REWARDS");

        mvc.perform(get("/api/v1/applications/{id}/journey", "APP-0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("APP-0001"))
                .andExpect(jsonPath("$.overallStatus").value(Application.IN_PROGRESS))
                .andExpect(jsonPath("$.events").isArray())
                // Parsed object, not the JSON-inside-a-string it used to be.
                .andExpect(jsonPath("$.application").isMap());
    }

    // ---- GET ?name=: the operator's search -----------------------------------------------

    @Test
    void nameSearchReturnsApplicationObjects() throws Exception {
        when(store.applicationsByName(eq("nowak"), anyInt()))
                .thenReturn(List.of(application("APP-0001", "Maria Nowak")));

        mvc.perform(get("/api/v1/applications").param("name", "nowak"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].applicationId").value("APP-0001"))
                .andExpect(jsonPath("$[0].applicant.fullName").value("Maria Nowak"));
    }

    /** Without the param it is still the board — one URL, two shapes, deliberately. */
    @Test
    void withoutNameItIsStillTheBoard() throws Exception {
        when(store.board(anyInt())).thenReturn(List.of());

        mvc.perform(get("/api/v1/applications"))
                .andExpect(status().isOk());

        verify(store).board(200);
        verify(store, never()).applicationsByName(any(), anyInt());
        verifyNoInteractions(simulations);
    }

    @Test
    void nameSearchNeverFallsThroughToSimulations() throws Exception {
        when(store.applicationsByName(eq("nowak"), anyInt())).thenReturn(List.of());

        mvc.perform(get("/api/v1/applications").param("name", "nowak"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        verifyNoInteractions(simulations);
    }

    // ---- POST /{id}/proceed: the demo button ----------------------------------------------

    @Test
    void proceedReportsTheStepItDispatched() throws Exception {
        when(engine.proceed("APP-0001")).thenReturn(Optional.of(3));

        mvc.perform(post("/api/v1/applications/{id}/proceed", "APP-0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value("APP-0001"))
                .andExpect(jsonPath("$.dispatchedStep").value(3));
    }

    /**
     * 409 rather than 404: the application usually exists, it simply is not parked — which is
     * what an operator sees when demo stepping is off, and the message has to say so.
     */
    @Test
    void proceedOnAnApplicationThatIsNotParkedIs409() throws Exception {
        when(engine.proceed("APP-0001")).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/applications/{id}/proceed", "APP-0001"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }
}
