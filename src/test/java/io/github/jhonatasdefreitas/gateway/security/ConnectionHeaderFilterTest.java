package io.github.jhonatasdefreitas.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

class ConnectionHeaderFilterTest {

    private final ConnectionHeaderFilter filter = new ConnectionHeaderFilter();

    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

    private final WebFilterChain chain = exchange -> {
        forwarded.set(exchange);
        return Mono.empty();
    };

    @Test
    void dropsTheHeadersNamedInConnectionAndConnectionItself() {
        HttpHeaders headers = forward(MockServerHttpRequest.get("/api/orders")
                .header("Connection", "close, X-User-Id")
                .header("Connection", "x-correlation-id")
                .header("X-User-Id", "someone-else")
                .header("X-Correlation-Id", "checkout-42")
                .header("Accept", "application/json"));

        assertThat(headers.containsHeader("Connection")).isFalse();
        assertThat(headers.containsHeader("X-User-Id")).isFalse();
        assertThat(headers.containsHeader("X-Correlation-Id")).isFalse();
        assertThat(headers.getFirst("Accept")).isEqualTo("application/json");
    }

    @Test
    void leavesARequestWithoutConnectionAsItCame() {
        HttpHeaders headers = forward(MockServerHttpRequest.get("/api/orders")
                .header("X-Correlation-Id", "checkout-42"));

        assertThat(headers.getFirst("X-Correlation-Id")).isEqualTo("checkout-42");
    }

    private HttpHeaders forward(MockServerHttpRequest.BaseBuilder<?> request) {
        filter.filter(MockServerWebExchange.from(request), chain).block();
        return forwarded.get().getRequest().getHeaders();
    }
}
