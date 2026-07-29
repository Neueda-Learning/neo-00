package com.neobank.orchestrator.config;

import java.util.Map;
import java.util.Optional;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientResponseException;

/**
 * A module the orchestrator was proxying for said no, or could not be reached.
 *
 * <p>Carries the status the customer should see rather than letting a downstream code leak
 * through unread. The two are usually the same — a module's {@code 404} for an unknown case is a
 * {@code 404} here too — but not always: a signature request the module would refuse {@code 400}
 * for a missing envelope is a {@code 409} to the customer, because from their side nothing is
 * malformed, the agreement simply is not ready to be signed.</p>
 *
 * <p>Rendered by {@link ApiExceptionHandler} into the same JSON shape as every other error, which
 * also keeps the message: {@code server.error.include-message: never} suppresses the container's
 * error page, not a body a controller advice returns.</p>
 */
public class UpstreamModuleException extends RuntimeException {

    private final HttpStatus status;

    public UpstreamModuleException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public UpstreamModuleException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /**
     * A module answered, and said no.
     *
     * <p>Its status is passed on rather than flattened: a {@code 404} (no such thing) and a
     * {@code 409} (there, but not in a state you may do that to) mean genuinely different things
     * to the page showing them. A {@code 5xx} is the exception — a module failing is our problem
     * to describe, not a stack trace for a customer to read — and becomes a {@code 502} with
     * {@code fallback} as its words.</p>
     */
    public static UpstreamModuleException from(RestClientResponseException e, String fallback) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        if (status == null || status.is5xxServerError()) {
            return new UpstreamModuleException(HttpStatus.BAD_GATEWAY, fallback, e);
        }
        return new UpstreamModuleException(status, messageFrom(e).orElse(fallback), e);
    }

    /** Nothing answered at all — a container that is down, starting, or unroutable. */
    public static UpstreamModuleException unreachable(String message, Throwable cause) {
        return new UpstreamModuleException(HttpStatus.BAD_GATEWAY, message, cause);
    }

    /** A module's own error body carries the useful sentence; keep it when there is one. */
    private static Optional<String> messageFrom(RestClientResponseException e) {
        try {
            Map<String, Object> body = e.getResponseBodyAs(
                    new ParameterizedTypeReference<Map<String, Object>>() {});
            Object message = body == null ? null : body.get("message");
            return message == null ? Optional.empty() : Optional.of(String.valueOf(message));
        } catch (Exception ignored) {
            // A module that cannot even shape its errors is not worth a second failure here.
            return Optional.empty();
        }
    }
}
