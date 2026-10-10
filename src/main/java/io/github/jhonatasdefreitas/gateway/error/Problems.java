package io.github.jhonatasdefreitas.gateway.error;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Writes the errors the gateway itself answers (401, 403, 429, 503...) as {@code application/problem+json}.
 */
@Component
public class Problems {

    private static final URI BLANK_TYPE = URI.create("about:blank");

    private static final Map<HttpStatus, String> TITLES = Map.of(
            HttpStatus.UNAUTHORIZED, "Não autenticado",
            HttpStatus.FORBIDDEN, "Acesso negado",
            HttpStatus.NOT_FOUND, "Não encontrado",
            HttpStatus.TOO_MANY_REQUESTS, "Muitas requisições",
            HttpStatus.SERVICE_UNAVAILABLE, "Serviço indisponível",
            HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno");

    private final ServerResponse.Context context;

    Problems(ServerCodecConfigurer codecs) {
        List<HttpMessageWriter<?>> writers = codecs.getWriters();
        this.context = new ServerResponse.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return writers;
            }

            @Override
            public List<ViewResolver> viewResolvers() {
                return List.of();
            }
        };
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(BLANK_TYPE);
        problem.setTitle(TITLES.getOrDefault(status, status.getReasonPhrase()));
        problem.setInstance(URI.create(originalPath(exchange)));
        return ServerResponse.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyValue(problem)
                .flatMap(response -> response.writeTo(exchange, context));
    }

    // Route filters rewrite the path (strip prefix, fallback forward); the client should see the one it called.
    private static String originalPath(ServerWebExchange exchange) {
        Set<URI> originalUrls = exchange.getAttributeOrDefault(GATEWAY_ORIGINAL_REQUEST_URL_ATTR, Set.of());
        return originalUrls.stream().findFirst()
                .orElseGet(() -> exchange.getRequest().getURI())
                .getRawPath();
    }
}
