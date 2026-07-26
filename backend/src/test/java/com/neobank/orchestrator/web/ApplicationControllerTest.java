package com.neobank.orchestrator.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neobank.orchestrator.domain.Application;
import com.neobank.orchestrator.generator.GeneratorService;
import com.neobank.orchestrator.saga.SagaDtos.ApplicationDetail;
import com.neobank.orchestrator.saga.SagaDtos.ApplicationStatusUpdate;
import com.neobank.orchestrator.saga.SagaEngine;
import com.neobank.orchestrator.saga.SagaStore;
import java.util.List;
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

    private void stubDetail(String id, String applicant, String product) {
        when(store.detail(id)).thenReturn(Optional.of(
                new ApplicationDetail(id, applicant, product, 8000, "WEB", 0,
                        Application.IN_PROGRESS, "{}", null, null, List.of())));
    }

    @Test
    void aSubmittedApplicationIsCreatedFromTheBody() throws Exception {
        Application app = new Application("APP-0007", "corr", "Ada Byron",
                "CREDIT_CARD_PLATINUM", 8000, "WEB", "{}");
        when(generator.createAndStart(anyMap())).thenReturn(app);
        stubDetail("APP-0007", "Ada Byron", "CREDIT_CARD_PLATINUM");

        mvc.perform(post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channel":"WEB",
                                 "applicant":{"fullName":"Ada Byron"},
                                 "product":{"productCode":"CREDIT_CARD_PLATINUM",
                                            "requestedCreditLimit":8000}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("APP-0007"))
                .andExpect(jsonPath("$.productCode").value("CREDIT_CARD_PLATINUM"));

        verify(generator).createAndStart(anyMap());
    }

    @Test
    void noBodyStillGeneratesAFixture() throws Exception {
        Application app = new Application("APP-0001", "corr", "Maria Nowak",
                "CREDIT_CARD_PREMIUM", 3000, "MOBILE_APP", "{}");
        when(generator.createAndStart()).thenReturn(app);
        stubDetail("APP-0001", "Maria Nowak", "CREDIT_CARD_PREMIUM");

        mvc.perform(post("/api/v1/applications"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("APP-0001"));

        verify(generator).createAndStart();
    }

    @Test
    void aSubmissionMissingTheApplicantIsRejected() throws Exception {
        mvc.perform(post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":{\"productCode\":\"CREDIT_CARD_PLATINUM\"}}"))
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
}
