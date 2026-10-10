package io.github.jhonatasdefreitas.gateway.error;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import io.github.jhonatasdefreitas.gateway.logging.CorrelationIdFilter;
import reactor.core.publisher.Mono;

/**
 * Last stop for errors that no route fallback handled, such as a stream route whose service is down.
 */
@Component
@Order(-1) // ahead of WebFlux's own handler, which answers a ResponseStatusException with an empty body
class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private final Problems problems;

    GatewayErrorHandler(Problems problems) {
        this.problems = problems;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(error);
        }
        HttpStatus status = statusFor(error);
        if (status.is5xxServerError()) {
            log.warn("{} {} failed with {}: {} correlationId={}", exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(), status.value(), error.toString(),
                    CorrelationIdFilter.correlationId(exchange));
        }
        return problems.write(exchange, status, detailFor(status));
    }

    static HttpStatus statusFor(Throwable error) {
        if (error instanceof ResponseStatusException statusException) {
            HttpStatus status = HttpStatus.resolve(statusException.getStatusCode().value());
            return status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR;
        }
        Throwable root = NestedExceptionUtils.getMostSpecificCause(error);
        if (root instanceof IOException || root instanceof TimeoutException) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static String detailFor(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "Este caminho não existe no gateway.";
            case SERVICE_UNAVAILABLE -> "O serviço não respondeu. Tente de novo em instantes.";
            case INTERNAL_SERVER_ERROR -> "Erro inesperado no gateway.";
            default -> "A requisição não pôde ser atendida.";
        };
    }
}
