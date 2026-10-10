package io.github.jhonatasdefreitas.gateway.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.CorsProcessor;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.cors.reactive.DefaultCorsProcessor;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import io.github.jhonatasdefreitas.gateway.error.Problems;
import reactor.core.publisher.Mono;

class CorsFilter implements WebFilter {

    private final CorsConfigurationSource configurations;
    private final CorsProcessor processor = new DefaultCorsProcessor();
    private final Problems problems;

    CorsFilter(CorsConfigurationSource configurations, Problems problems) {
        this.configurations = configurations;
        this.problems = problems;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        boolean accepted = processor.process(configurations.getCorsConfiguration(exchange), exchange);
        if (!accepted) {
            // Spring's CorsWebFilter does the same checks but stops here with an empty 403.
            return problems.write(exchange, HttpStatus.FORBIDDEN,
                    "Requisição recusada pelas regras de CORS do gateway.");
        }
        if (CorsUtils.isPreFlightRequest(exchange.getRequest())) {
            return Mono.empty();
        }
        return chain.filter(exchange);
    }
}
