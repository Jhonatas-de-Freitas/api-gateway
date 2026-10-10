package io.github.jhonatasdefreitas.gateway.logging;

import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class CorrelationIdFilter implements WebFilter {

    public static final String HEADER = "X-Correlation-Id";

    private static final String ATTRIBUTE = CorrelationIdFilter.class.getName() + ".correlationId";

    // The id ends up in log lines, so anything that is not a plain token is replaced instead of trusted.
    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    public static String correlationId(ServerWebExchange exchange) {
        return exchange.getAttributeOrDefault(ATTRIBUTE, "-");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String received = exchange.getRequest().getHeaders().getFirst(HEADER);
        String correlationId = received != null && ACCEPTED.matcher(received).matches()
                ? received
                : UUID.randomUUID().toString();

        exchange.getAttributes().put(ATTRIBUTE, correlationId);
        // Set right before the response goes out so it also replaces a copy echoed by the service behind.
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER, correlationId);
            return Mono.empty();
        });

        return chain.filter(exchange.mutate()
                .request(request -> request.headers(headers -> headers.set(HEADER, correlationId)))
                .build());
    }
}
