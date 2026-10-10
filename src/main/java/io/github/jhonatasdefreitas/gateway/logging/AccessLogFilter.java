package io.github.jhonatasdefreitas.gateway.logging;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class AccessLogFilter implements WebFilter {

    /** Filled in by the security chain once the token is accepted. */
    public static final String USER_ATTRIBUTE = AccessLogFilter.class.getName() + ".user";

    private static final Logger log = LoggerFactory.getLogger("access");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }
        long startedAt = System.nanoTime();
        // Logged when the response starts: the status is final by then, even when it comes from the error
        // handler, and a stream gets its line when it opens instead of when the client goes away.
        exchange.getResponse().beforeCommit(() -> {
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            log.info("{} {} {} {}ms user={} correlationId={}",
                    exchange.getRequest().getMethod(),
                    path,
                    status != null ? status.value() : 200,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                    exchange.getAttributeOrDefault(USER_ATTRIBUTE, "-"),
                    CorrelationIdFilter.correlationId(exchange));
            return Mono.empty();
        });
        return chain.filter(exchange);
    }
}
