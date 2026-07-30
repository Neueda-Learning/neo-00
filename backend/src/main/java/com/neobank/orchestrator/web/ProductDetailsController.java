package com.neobank.orchestrator.web;

import com.neobank.orchestrator.products.ProductDetailsClient;
import com.neobank.orchestrator.products.ProductDetailsClient.ProductDetailsView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Customer-safe read model for the card produced by a completed application. */
@RestController
@RequestMapping("/api/v1/applications/{id}/product-details")
public class ProductDetailsController {

    private final ProductDetailsClient details;

    public ProductDetailsController(ProductDetailsClient details) {
        this.details = details;
    }

    @GetMapping
    public ProductDetailsView productDetails(@PathVariable("id") String id) {
        return details.view(id);
    }
}
