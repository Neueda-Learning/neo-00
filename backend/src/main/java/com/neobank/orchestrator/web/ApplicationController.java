package com.neobank.orchestrator.web;

import com.neobank.orchestrator.domain.Application;
import com.neobank.orchestrator.generator.GeneratorService;
import com.neobank.orchestrator.saga.SagaDtos.ApplicationDetail;
import com.neobank.orchestrator.saga.SagaDtos.ApplicationStatusUpdate;
import com.neobank.orchestrator.saga.SagaDtos.BoardSummary;
import com.neobank.orchestrator.saga.SagaEngine;
import com.neobank.orchestrator.saga.SagaStore;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The application resource: create one, list them with their ten step statuses, read one, and —
 * the one the services use — update the status of one.
 *
 * <p>The status update is a {@code PUT} on the application rather than a post to a separate
 * callbacks endpoint, because that is what it is: a service changing the state of an application
 * this orchestrator owns. It lives here, with the rest of the resource.</p>
 */
@RestController
@RequestMapping("/api/v1/applications")
public class ApplicationController {

    private final SagaStore store;
    private final GeneratorService generator;
    private final SagaEngine engine;

    public ApplicationController(SagaStore store, GeneratorService generator, SagaEngine engine) {
        this.store = store;
        this.generator = generator;
        this.engine = engine;
    }

    /**
     * {@code PUT /api/v1/applications/{id}} — where the ten services report their answers. <b>This
     * is what actually drives the journey forward</b>; the {@code 202} a service gives on dispatch
     * only means "received".
     *
     * <p>Always answers {@code 200}, even for an update that changes nothing. A service must not be
     * left retrying because its late, duplicate or misdirected report was refused; it is recorded
     * either way, and {@link SagaStore} decides whether it matters.</p>
     *
     * <p>The id comes from the path, so the body carries only {@code serviceId}, {@code status} and
     * {@code comment}.</p>
     */
    @PutMapping("/{id}")
    public Map<String, Object> updateStatus(@PathVariable String id,
                                            @Valid @RequestBody ApplicationStatusUpdate update) {
        engine.handleApplicationStatusUpdate(id, update);
        return Map.of("received", true, "applicationId", id);
    }

    /**
     * Create one application and start its journey.
     *
     * <p>With <b>no body</b>, the payload is generated from the seeded fixture set — the
     * backoffice "+ one" button and the auto-generator both take this path. With a <b>body</b>,
     * the customer journey's filled-in Application object (api-contract §4 shape) is used as-is;
     * the orchestrator still owns the id and the submission timestamp.</p>
     */
    @PostMapping
    public ResponseEntity<ApplicationDetail> create(
            @RequestBody(required = false) Map<String, Object> application) {
        Application created = (application == null || application.isEmpty())
                ? generator.createAndStart()
                : generator.createAndStart(validated(application));
        return ResponseEntity
                .created(URI.create("/api/v1/applications/" + created.getId()))
                .body(store.detail(created.getId()).orElseThrow());
    }

    /** A submitted application must at least name an applicant and a product. */
    private static Map<String, Object> validated(Map<String, Object> application) {
        if (blank(nested(application, "applicant", "fullName"))) {
            throw new IllegalArgumentException("applicant.fullName is required");
        }
        if (blank(nested(application, "product", "productCode"))) {
            throw new IllegalArgumentException("product.productCode is required");
        }
        return application;
    }

    @SuppressWarnings("unchecked")
    private static String nested(Map<String, Object> map, String outer, String inner) {
        Object child = map.get(outer);
        if (child instanceof Map<?, ?> m) {
            Object value = ((Map<String, Object>) m).get(inner);
            return value == null ? null : String.valueOf(value);
        }
        return null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Without {@code name}: the board rows this orchestrator's own front end draws — id, applicant,
     * product and the ten step dots.
     *
     * <p>With {@code name}: the applications whose applicant name contains it, each as the
     * <b>api-contract §4 application object</b> — the shape a service expects, matching
     * {@link #get(String)}.</p>
     *
     * <p><b>Yes, one URL with two response shapes.</b> That is a wart and it is deliberate: the
     * bare collection is a UI view that predates the search, and changing it would break the board
     * screen and anything already reading it. The tidy version moves the board to its own path;
     * that is not worth doing mid-hackathon.</p>
     */
    @GetMapping
    public List<?> board(@RequestParam(defaultValue = "200") int limit,
                         @RequestParam(required = false) String name) {
        int capped = Math.clamp(limit, 1, 1000);
        return name == null ? store.board(capped) : store.applicationsByName(name, capped);
    }

    @GetMapping("/summary")
    public BoardSummary summary() {
        return store.boardSummary();
    }

    /**
     * {@code GET /api/v1/applications/{id}} — <b>the application itself</b>, in the api-contract §4
     * shape: the same object a service is handed in the {@code application} field of its dispatch
     * envelope.
     *
     * <p>This is the endpoint a service calls when it needs applicant data it correctly did not
     * store locally. One object, two ways to get it — pushed, or pulled — and they are identical,
     * which is the only reason pulling it is worth anything.</p>
     *
     * <p><b>404 on an unknown id</b>, not an empty object. Services map that status onto their own
     * "no such application" error, so it has to stay a 404.</p>
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return ResponseEntity.of(store.application(id));
    }

    /**
     * {@code GET /api/v1/applications/{id}/journey} — the orchestrator's own view: board row,
     * application, and the full append-only event log. What the front end's detail drawer reads.
     */
    @GetMapping("/{id}/journey")
    public ResponseEntity<ApplicationDetail> journey(@PathVariable String id) {
        return ResponseEntity.of(store.detail(id));
    }
}
