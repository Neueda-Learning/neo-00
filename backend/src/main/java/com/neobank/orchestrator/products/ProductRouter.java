package com.neobank.orchestrator.products;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Differentiates an incoming application by its product code and dispatches it to the matching
 * {@link ProductHandler} — the "route the two products to two handlers" step.
 *
 * <p><b>Record-only.</b> Routing is a demonstration hook: the handler logs which product it is,
 * then the normal journey runs and the outcome comes from the downstream service. An unknown or
 * absent product is logged and skipped — routing never blocks a journey.</p>
 */
@Service
public class ProductRouter {

    private static final Logger log = LoggerFactory.getLogger(ProductRouter.class);

    private final Map<String, ProductHandler> byCode;

    public ProductRouter(List<ProductHandler> handlers) {
        this.byCode = handlers.stream()
                .collect(Collectors.toUnmodifiableMap(ProductHandler::productCode, Function.identity()));
    }

    /** Route one application to its product's handler. Unknown products are logged, not blocked. */
    public void route(String productCode, String applicationId, Map<String, Object> application) {
        ProductHandler handler = productCode == null ? null : byCode.get(productCode);
        if (handler == null) {
            log.warn("No product handler for '{}' ({}) — routing skipped", productCode, applicationId);
            return;
        }
        handler.handle(applicationId, application);
    }
}
