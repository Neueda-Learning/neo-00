package com.neobank.orchestrator.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neobank.orchestrator.domain.ApplicationRepository;
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
 * Signing in, and what a customer is shown.
 *
 * <p>Through HTTP against a real database rather than with a mocked service, because the two
 * things most worth pinning — that {@code ab12} and {@code AB12} are one customer, and that one
 * customer's applications are not another's — both live in the layers a mock would replace.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// Its own database: this class asserts on exactly which applications a customer has, and the
// default H2 URL is shared with every other test context in the module.
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:customerapi;MODE=MySQL;DB_CLOSE_DELAY=-1")
class CustomerApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationRepository applications;

    /** Dispatches would otherwise fire at services that are not there. */
    @MockBean(answer = Answers.RETURNS_DEEP_STUBS)
    RestClient restClient;

    // ---- signing in ----

    /**
     * With an {@code Origin} header, which is what makes this the browser's request and not
     * curl's. Signing in is a PUT, and it was the first one any browser made here — the module
     * status update is also a PUT but comes from a Java client that sends no Origin, so CORS
     * never applied to it. Without PUT in the allowed methods Chrome gets a bare {@code 403}
     * while the identical curl gets {@code 200}.
     */
    @Test
    void signingInWorksFromABrowserAndNotOnlyFromCurl() throws Exception {
        mvc.perform(put("/api/v1/customers/BR01").header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value("BR01"));
    }

    @Test
    void anUnusedCodeIsCreatedAndHasNothingYet() throws Exception {
        mvc.perform(put("/api/v1/customers/ZZ99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value("ZZ99"))
                .andExpect(jsonPath("$.isNew").value(true))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void signingInTwiceIsTheSameCustomerAndNoLongerNew() throws Exception {
        mvc.perform(put("/api/v1/customers/ZY98")).andExpect(jsonPath("$.isNew").value(true));

        mvc.perform(put("/api/v1/customers/ZY98"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isNew").value(false));
    }

    /**
     * The one that would only fail on the real stack. MySQL 8's default collation is
     * case-insensitive and H2's comparison is not, so without normalising in a single place
     * {@code ab12} is one customer in production and a second, empty one in these tests.
     */
    @Test
    void aCodeIsTheSameCustomerWhateverCaseItIsTypedIn() throws Exception {
        mvc.perform(put("/api/v1/customers/ab12")).andExpect(jsonPath("$.isNew").value(true));

        mvc.perform(put("/api/v1/customers/AB12"))
                .andExpect(jsonPath("$.customerId").value("AB12"))
                .andExpect(jsonPath("$.isNew").value(false));
        mvc.perform(get("/api/v1/customers/Ab12")).andExpect(status().isOk());
    }

    @Test
    void aCodeThatIsNotTwoLettersAndTwoDigitsIsRefusedWithSomethingReadable() throws Exception {
        for (String bad : new String[]{"AB1", "ABCD", "1234", "12AB", "A_12"}) {
            mvc.perform(put("/api/v1/customers/" + bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("two letters then two digits")));
        }
    }

    // ---- reading one back ----

    @Test
    void anUnknownCodeIsNotFoundWhichIsHowTheLoginHintKnowsItIsFree() throws Exception {
        mvc.perform(get("/api/v1/customers/QQ11")).andExpect(status().isNotFound());
    }

    // ---- what they have ----

    @Test
    void aCustomerSeesTheirOwnApplicationsAndNobodyElses() throws Exception {
        mvc.perform(put("/api/v1/customers/CC11"));
        mvc.perform(put("/api/v1/customers/DD22"));
        submitAs("CC11");
        submitAs("CC11");
        submitAs("DD22");

        mvc.perform(get("/api/v1/customers/CC11"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].kind").value("APPLICATION"))
                .andExpect(jsonPath("$.items[0].productCode").value("CREDIT_CARD_STANDARD"));
        mvc.perform(get("/api/v1/customers/DD22"))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    /**
     * The generator's fixtures and the backoffice's "+ one" carry no code, so they fill the
     * operator's board and appear on nobody's own screen. This holds only while nothing defaults
     * {@code customerId} to a non-null value.
     */
    @Test
    void anApplicationCreatedWithoutACodeBelongsToNobody() throws Exception {
        mvc.perform(post("/api/v1/applications")).andExpect(status().isCreated());

        assertThat(applications.findAll())
                .anyMatch(a -> a.getCustomerId() == null);
    }

    /**
     * The assertion that makes the query-parameter decision a rule rather than a comment: the
     * code identifies the customer to US and must not reach the ten modules, which bind the
     * application object into typed records.
     */
    @Test
    void theCodeIsNotWrittenIntoTheApplicationPayload() throws Exception {
        mvc.perform(put("/api/v1/customers/EE33"));
        String id = submitAs("EE33");

        String payload = applications.findById(id).orElseThrow().getPayloadJson();
        assertThat(payload).doesNotContain("customerId").doesNotContain("EE33");
    }

    @Test
    void submittingForACodeThatDoesNotExistIsRefused() throws Exception {
        mvc.perform(post("/api/v1/applications?customerId=XX77")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(APPLICATION))
                .andExpect(status().isNotFound());
    }

    private static final String APPLICATION = """
            {"channel":"WEB",
             "applicant":{"fullName":"Ada Lovelace","dateOfBirth":"1990-05-15"},
             "product":{"productCode":"CREDIT_CARD_STANDARD","requestedCreditLimit":3000}}""";

    /** Submit one application for a customer, returning its id. */
    private String submitAs(String code) throws Exception {
        String body = mvc.perform(post("/api/v1/applications?customerId=" + code)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(APPLICATION))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.id");
    }
}
