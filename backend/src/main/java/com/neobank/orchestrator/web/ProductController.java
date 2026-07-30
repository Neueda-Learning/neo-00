package com.neobank.orchestrator.web;

import com.neobank.orchestrator.products.ProductCatalogue;
import com.neobank.orchestrator.products.ProductCatalogue.CatalogueEntry;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The products a customer may apply for, as the module that owns them currently defines them.
 *
 * <p>Proxied rather than served from here on purpose — see {@link ProductCatalogue} for why the
 * orchestrator must not hold its own copy. An empty list means the owning module is unreachable,
 * not that the bank has stopped selling cards; the customer's product picker treats it that way.</p>
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductCatalogue catalogue;

    public ProductController(ProductCatalogue catalogue) {
        this.catalogue = catalogue;
    }

    @GetMapping
    public List<CatalogueEntry> products() {
        return catalogue.current();
    }
}
