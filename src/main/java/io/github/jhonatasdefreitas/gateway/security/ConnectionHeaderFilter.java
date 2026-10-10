package io.github.jhonatasdefreitas.gateway.security;

import java.util.List;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ConnectionHeaderFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        List<String> named = exchange.getRequest().getHeaders().getConnection();
        if (named.isEmpty()) {
            return chain.filter(exchange);
        }
        // Headers named in Connection are for the first hop only, and the gateway drops them when it calls
        // a service. Left to that step, the removal also hits what the gateway adds: a caller could name
        // X-User-Id and reach a service with no identity. So they go here, before anything is added.
        return chain.filter(exchange.mutate()
                .request(request -> request.headers(headers -> {
                    named.forEach(headers::remove);
                    headers.remove(HttpHeaders.CONNECTION);
                }))
                .build());
    }
}
