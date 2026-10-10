package io.github.jhonatasdefreitas.gateway.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

class CorrelationIdFilterTest {

    private static final String UUID_PATTERN = "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}";

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

    private final WebFilterChain chain = exchange -> {
        forwarded.set(exchange);
        return exchange.getResponse().setComplete();
    };

    @Test
    void createsAnIdWhenTheRequestHasNone() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/products"));

        filter.filter(exchange, chain).block();

        String forwardedId = forwarded.get().getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER);
        assertThat(forwardedId).matches(UUID_PATTERN);
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdFilter.HEADER)).isEqualTo(forwardedId);
        assertThat(CorrelationIdFilter.correlationId(exchange)).isEqualTo(forwardedId);
    }

    @Test
    void keepsTheIdOfTheCaller() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/products")
                .header(CorrelationIdFilter.HEADER, "checkout-42"));

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get().getRequest().getHeaders().get(CorrelationIdFilter.HEADER))
                .containsExactly("checkout-42");
        assertThat(exchange.getResponse().getHeaders().get(CorrelationIdFilter.HEADER))
                .containsExactly("checkout-42");
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "   ", "two words", "<script>alert(1)</script>", "line\tbreak",
            "0123456789012345678901234567890123456789012345678901234567890123456789" })
    void replacesAnIdThatCouldPolluteTheLog(String received) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/products")
                .header(CorrelationIdFilter.HEADER, received));

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get().getRequest().getHeaders().get(CorrelationIdFilter.HEADER))
                .singleElement().asString().matches(UUID_PATTERN);
    }

    @Test
    void responseKeepsTheIdEvenWhenSomethingElseSetsTheHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/products")
                .header(CorrelationIdFilter.HEADER, "checkout-42"));
        WebFilterChain echoingService = forwardedExchange -> {
            forwardedExchange.getResponse().getHeaders().add(CorrelationIdFilter.HEADER, "from-the-service");
            return forwardedExchange.getResponse().setComplete();
        };

        filter.filter(exchange, echoingService).block();

        assertThat(exchange.getResponse().getHeaders().get(CorrelationIdFilter.HEADER))
                .containsExactly("checkout-42");
    }
}
