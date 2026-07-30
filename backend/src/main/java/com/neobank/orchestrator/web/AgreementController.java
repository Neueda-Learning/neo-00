package com.neobank.orchestrator.web;

import com.neobank.orchestrator.agreement.AgreementClient;
import com.neobank.orchestrator.agreement.AgreementClient.AgreementView;
import com.neobank.orchestrator.agreement.AgreementClient.SignedDocument;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's side of the agreement step: read your credit agreement, then sign or decline it.
 *
 * <h2>Why the orchestrator is in the middle</h2>
 *
 * <p>The agreement lives in another service and another container. A browser could in principle
 * call it directly, but then the customer's page needs that service's address, its CORS policy has
 * to admit this origin, and on AWS — where every module sits behind its own path prefix on a
 * shared load balancer — the address is different again. Proxying costs one hop and keeps the rule
 * the whole system is built on: <b>the orchestrator is the only door.</b></p>
 *
 * <h2>What this does NOT do</h2>
 *
 * <p><b>It does not advance the journey.</b> Signing is a fact reported to the module that owns
 * the agreement; whether that means the journey continues is the module's answer, sent back on the
 * ordinary status update like every other decision in the system. An orchestrator that moved the
 * journey on by itself here would be deciding on a team's behalf, and the module's own answer
 * would then arrive at a step that had already moved past it.</p>
 *
 * <p>These handlers run on Tomcat request threads, not the saga scheduler pool, so the short
 * timeouts on the shared {@code RestClient} — sized for a dispatch that must return a {@code 202}
 * in milliseconds — are not a constraint here and are deliberately not raised.</p>
 */
@RestController
@RequestMapping("/api/v1/applications/{id}/agreement")
public class AgreementController {

    private final AgreementClient agreements;

    public AgreementController(AgreementClient agreements) {
        this.agreements = agreements;
    }

    /** The terms as the customer needs to see them. */
    @GetMapping
    public AgreementView agreement(@PathVariable("id") String id) {
        return agreements.view(id);
    }

    /**
     * The agreement itself, as a PDF the page can embed.
     *
     * <p>{@code no-store} because an agreement is not the sort of thing to leave in a browser
     * cache, and because a resent envelope replaces the document under the same URL.</p>
     */
    @GetMapping("/document")
    public ResponseEntity<byte[]> document(@PathVariable("id") String id) {
        SignedDocument document = agreements.document(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + document.fileName() + "\"")
                .body(document.content());
    }

    @PostMapping("/sign")
    public Map<String, Object> sign(@PathVariable("id") String id) {
        return agreements.decide(id, "SIGNED");
    }

    @PostMapping("/decline")
    public Map<String, Object> decline(@PathVariable("id") String id) {
        return agreements.decide(id, "DECLINED");
    }
}
