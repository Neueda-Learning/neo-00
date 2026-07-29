package com.neobank.orchestrator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets the React front end (its nginx container, or the Vite dev server) call the API.
 *
 * <p><b>Patterns, not a fixed list.</b> The browser sends an {@code Origin} header on every
 * POST — including one that nginx proxies same-origin — and Spring answers {@code 403
 * Invalid CORS request} if that origin is not allowed. With a hard-coded
 * {@code localhost:3000} the generator toggle therefore dies silently the moment anyone
 * runs the stack on a different {@code UI_PORT}, which the README explicitly tells them to
 * do when 3000 is taken. Same-origin GETs send no {@code Origin}, so only writes broke —
 * which is exactly the kind of thing that passes every curl check and fails in the browser.
 *
 * <p>This is a single-user local stack with no auth, so any localhost port is fine.</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${cors.allowed-origin-patterns:http://localhost:*,http://127.0.0.1:*}")
    private String[] allowedOriginPatterns;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                // PUT is here because signing in is one, and it was the FIRST put a browser ever
                // made: the other one in this system — a module reporting its status — comes from
                // a Java client, which sends no Origin header, so CORS never applied to it and
                // the gap was invisible. Chrome got a bare 403 while the identical curl got 200,
                // which is precisely the failure the note above describes.
                .allowedMethods("GET", "POST", "PUT", "OPTIONS")
                .allowedHeaders("*");
    }
}
