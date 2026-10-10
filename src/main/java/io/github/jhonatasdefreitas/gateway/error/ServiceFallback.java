package io.github.jhonatasdefreitas.gateway.error;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import io.github.jhonatasdefreitas.gateway.logging.CorrelationIdFilter;
import reactor.core.publisher.Mono;

/**
 * Where a route lands when its service is down, too slow, or its circuit is open.
 */
@RestController
public class ServiceFallback {

    private static final Logger log = LoggerFactory.getLogger(ServiceFallback.class);

    private static final String PATH = "/fallback/";

    private final Problems problems;

    ServiceFallback(Problems problems) {
        this.problems = problems;
    }

    public static String uriFor(String service) {
        return "forward:" + PATH + service;
    }

    @RequestMapping(PATH + "{service}")
    Mono<Void> unavailable(@PathVariable String service, ServerWebExchange exchange) {
        Throwable cause = exchange.getAttribute(CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        log.warn("{} unavailable: {} correlationId={}", service, cause, CorrelationIdFilter.correlationId(exchange));
        return problems.write(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                "O " + service + " não está respondendo. Tente de novo em instantes.");
    }
}
