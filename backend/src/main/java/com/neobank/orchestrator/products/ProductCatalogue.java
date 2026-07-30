package com.neobank.orchestrator.products;

import com.neobank.orchestrator.saga.ServiceRegistry;
import com.neobank.orchestrator.saga.ServiceRegistry.ServiceDef;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * What the bank is actually selling right now, read from the module that decides it.
 *
 * <h2>Why this is not a constant</h2>
 *
 * <p>The orchestrator does not own the product catalogue — <b>neo-01 does</b>, and it rejects any
 * application naming a code it does not hold, or a limit outside that code's range
 * ({@code VER_INVALID_FIELD:product.productCode}, {@code VER_PRODUCT_INACTIVE}). For most of this
 * project the customer form offered two products neo-01 had never heard of, so <em>every</em>
 * submission died at step 1 and no part of the journey below verification could be demonstrated
 * at all. A catalogue copied into a constant here is that bug waiting to happen again: neo-01's
 * config is versioned and its team adds versions, six of them for one product in a single week.</p>
 *
 * <h2>Whose rule decides which version counts</h2>
 *
 * <p>neo-01's, not a rule reimplemented here. It selects the highest version and then checks
 * {@code active}, and its own {@code /products/{code}/versions} view marks that row
 * {@code current}. So this reads the flag rather than re-deriving it — reading only the seed
 * changesets gives the wrong answer twice over, since one "latest" version is inactive and a
 * later file supersedes it.</p>
 *
 * <p><b>Unavailability is not an error.</b> If neo-01 cannot be reached this returns an empty
 * list and the customer's product picker falls back to its own copy, which is the same two codes
 * with the same ranges. A bank whose verification service is down should still be able to show
 * somebody a credit card.</p>
 */
@Service
public class ProductCatalogue {

    private static final Logger log = LoggerFactory.getLogger(ProductCatalogue.class);

    /**
     * One product as the customer form needs it: the code to submit and the range to bound the
     * "how much would you like" field to. Deliberately narrower than neo-01's own view — the
     * version number and effective dates are its business, not a customer's.
     */
    public record CatalogueEntry(String productCode, Integer minAge, Integer limitMin,
                                 Integer limitMax, List<String> channels) {
    }

    /** neo-01's {@code /products/{code}/versions} row. Fields we do not use are ignored. */
    private record ProductVersion(String productCode, Integer minAge, Integer limitMin,
                                  Integer limitMax, Boolean active, List<String> channels,
                                  Boolean current) {
    }

    private final RestClient http;
    private final ServiceRegistry registry;
    private final String serviceId;

    public ProductCatalogue(RestClient http, ServiceRegistry registry,
                            @Value("${orchestrator.catalogue.service-id:neo01}") String serviceId) {
        this.http = http;
        this.registry = registry;
        this.serviceId = serviceId;
    }

    /** Every product on sale, or an empty list if the owning module cannot be reached. */
    public List<CatalogueEntry> current() {
        ServiceDef service = registry.byServiceId(serviceId);
        if (service == null) {
            log.warn("No service '{}' in the journey — cannot read the product catalogue", serviceId);
            return List.of();
        }

        List<String> codes;
        try {
            codes = http.get()
                    .uri(service.baseUrl() + "/products")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Could not read the product catalogue from {}: {}", serviceId, e.toString());
            return List.of();
        }
        if (codes == null) {
            return List.of();
        }

        List<CatalogueEntry> entries = new ArrayList<>();
        for (String code : codes) {
            currentVersion(service, code)
                    .filter(v -> Boolean.TRUE.equals(v.active()))
                    .map(v -> new CatalogueEntry(v.productCode(), v.minAge(), v.limitMin(),
                            v.limitMax(), v.channels()))
                    .ifPresent(entries::add);
        }
        return entries;
    }

    /**
     * One product's live version. A single unreadable product is skipped rather than failing the
     * whole catalogue — one code neo-01 is unhappy about should not empty the shop window.
     */
    private java.util.Optional<ProductVersion> currentVersion(ServiceDef service, String code) {
        try {
            List<ProductVersion> versions = http.get()
                    .uri(service.baseUrl() + "/products/" + code + "/versions")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<ProductVersion>>() {});
            if (versions == null) {
                return java.util.Optional.empty();
            }
            return versions.stream().filter(v -> Boolean.TRUE.equals(v.current())).findFirst();
        } catch (Exception e) {
            log.warn("Could not read versions of {} from {}: {}", code, serviceId, e.toString());
            return java.util.Optional.empty();
        }
    }
}
