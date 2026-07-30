package com.neobank.orchestrator.web;

import com.neobank.orchestrator.config.UpstreamModuleException;
import com.neobank.orchestrator.customer.CustomerService;
import com.neobank.orchestrator.domain.Application;
import com.neobank.orchestrator.domain.Customer;
import com.neobank.orchestrator.generator.GeneratorService;
import com.neobank.orchestrator.saga.SagaDtos.ApplicationDetail;
import com.neobank.orchestrator.saga.SagaDtos.ApplicationStatusUpdate;
import com.neobank.orchestrator.saga.SagaDtos.BoardSummary;
import com.neobank.orchestrator.saga.SagaEngine;
import com.neobank.orchestrator.saga.SagaStore;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
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
    private final CustomerService customers;

    public ApplicationController(SagaStore store, GeneratorService generator, SagaEngine engine,
                                CustomerService customers) {
        this.store = store;
        this.generator = generator;
        this.engine = engine;
        this.customers = customers;
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
     *
     * <h2>{@code ?customerId=AB12}</h2>
     *
     * <p>Who is applying, when somebody signed in on the customer surface. <b>A query parameter
     * and not a field of the body</b>, because the body is the api-contract §4 object that every
     * module binds into a typed record, and this orchestrator cannot verify from here that all
     * ten of them tolerate a key they have never seen. It is stored on the row and never written
     * into the payload.</p>
     *
     * <p>The rule has no exception: the parameter names the customer <em>whatever</em> the body,
     * including on the fixture path, which is how a customer's history can be seeded without
     * filling the form eight times. Omitted, the application belongs to nobody — which is right
     * for the generator and for "+ one".</p>
     *
     * <p>An <b>unknown code is a {@code 404}</b>, not a silent create: a typo must not orphan an
     * application that surfaces later when somebody signs in with that code.</p>
     */
    @PostMapping
    public ResponseEntity<ApplicationDetail> create(
            @RequestBody(required = false) Map<String, Object> application,
            @RequestParam(name = "customerId", required = false) String customerId) {
        String customer = resolveCustomer(customerId);
        Application created = (application == null || application.isEmpty())
                ? generator.createAndStart(customer)
                : generator.createAndStart(validated(application), customer);
        return ResponseEntity
                .created(URI.create("/api/v1/applications/" + created.getId()))
                .body(store.detail(created.getId()).orElseThrow());
    }

    /** Null stays null; anything else must be a code that has actually been signed in with. */
    private String resolveCustomer(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            return null;
        }
        String code = Customer.normalise(customerId);
        if (!customers.exists(code)) {
            throw new UpstreamModuleException(HttpStatus.NOT_FOUND,
                    "no customer " + code + " — sign in before applying");
        }
        return code;
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

    /**
     * {@code POST /api/v1/applications/{id}/proceed} — send the step a journey parked by demo
     * stepping is waiting on. The Proceed button on the board.
     *
     * <p><b>This releases a dispatch; it does not answer for a module.</b> The service still
     * decides and still reports its own status.</p>
     *
     * <p><b>409</b>, not 404, when the application is not parked — the id may well exist, it is
     * the state that is wrong, and saying so is how an operator learns the toggle is off.</p>
     */
    @PostMapping("/{id}/proceed")
    public ResponseEntity<Map<String, Object>> proceed(@PathVariable String id) {
        return engine.proceed(id)
                .map(step -> ResponseEntity.ok(Map.<String, Object>of(
                        "applicationId", id, "dispatchedStep", step)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "timestamp", Instant.now().toString(),
                        "status", HttpStatus.CONFLICT.value(),
                        "error", HttpStatus.CONFLICT.getReasonPhrase(),
                        "message", id + " is not waiting for an operator — it is either already "
                                + "running, finished, or unknown")));
    }
}
