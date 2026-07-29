package com.neobank.orchestrator.web;

import com.neobank.orchestrator.support.SupportClient;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Something is wrong with my application" — the customer's way into the support desk.
 *
 * <p>Proxied for the same reasons as the agreement (see {@link AgreementController}), with one
 * more: the support module is not part of the journey and has no address the saga knows, so this
 * is the only route to it that exists.</p>
 */
@RestController
@RequestMapping("/api/v1/applications/{id}/support-case")
public class SupportController {

    /**
     * What the customer tells us. The category is one of the module's own taxonomy codes — it
     * validates the word and refuses one it does not know, so there is nothing to check twice
     * here.
     */
    public record OpenCaseRequest(@NotBlank String category, @NotBlank String description) {
    }

    private final SupportClient support;

    public SupportController(SupportClient support) {
        this.support = support;
    }

    /** {@code 202}, matching the module's own answer: a case is opened, not answered, on request. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> openCase(@PathVariable("id") String id,
                                                        @Valid @RequestBody OpenCaseRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(support.openCase(id, request.category(), request.description()));
    }
}
