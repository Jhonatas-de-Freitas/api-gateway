package io.github.jhonatasdefreitas.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import io.github.jhonatasdefreitas.gateway.logging.AccessLogFilter;
import reactor.core.publisher.Mono;

class UserHeadersFilterTest {

    private final UserHeadersFilter filter = new UserHeadersFilter();

    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

    private final WebFilterChain chain = exchange -> {
        forwarded.set(exchange);
        return Mono.empty();
    };

    @Test
    void copiesTheUserFromTheToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders"));

        runAs(ana(), exchange);

        HttpHeaders headers = forwarded.get().getRequest().getHeaders();
        assertThat(headers.getFirst("X-User-Id")).isEqualTo("3f0c2a2e-ana");
        assertThat(headers.getFirst("X-User-Name")).isEqualTo("Ana Souza");
        assertThat(headers.getFirst("X-User-Email")).isEqualTo("ana@exemplo.com");
        assertThat(headers.getFirst("X-User-Roles")).isEqualTo("customer");
        assertThat(exchange.<String>getAttribute(AccessLogFilter.USER_ATTRIBUTE)).isEqualTo("ana");
    }

    @Test
    void tokenWinsOverHeadersSentByTheCaller() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders")
                .header("X-User-Id", "someone-else")
                .header("x-user-roles", "admin"));

        runAs(ana(), exchange);

        HttpHeaders headers = forwarded.get().getRequest().getHeaders();
        assertThat(headers.get("X-User-Id")).containsExactly("3f0c2a2e-ana");
        assertThat(headers.get("X-User-Roles")).containsExactly("customer");
    }

    @Test
    void withoutLoginNoUserHeaderGoesThrough() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/products")
                .header("X-User-Id", "someone-else")
                .header("X-User-Name", "Admin Loja")
                .header("X-User-Email", "admin@exemplo.com")
                .header("X-User-Roles", "admin")
                .header("Accept", "application/json"));

        filter.filter(exchange, chain).block();

        HttpHeaders headers = forwarded.get().getRequest().getHeaders();
        assertThat(headers.containsHeader("X-User-Id")).isFalse();
        assertThat(headers.containsHeader("X-User-Name")).isFalse();
        assertThat(headers.containsHeader("X-User-Email")).isFalse();
        assertThat(headers.containsHeader("X-User-Roles")).isFalse();
        assertThat(headers.getFirst("Accept")).isEqualTo("application/json");
        assertThat(exchange.getAttributes()).doesNotContainKey(AccessLogFilter.USER_ATTRIBUTE);
    }

    @Test
    void leavesOutWhatTheTokenDoesNotHave() {
        Jwt serviceAccount = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("service-account-1")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders"));

        runAs(serviceAccount, exchange);

        HttpHeaders headers = forwarded.get().getRequest().getHeaders();
        assertThat(headers.getFirst("X-User-Id")).isEqualTo("service-account-1");
        assertThat(headers.containsHeader("X-User-Name")).isFalse();
        assertThat(headers.containsHeader("X-User-Email")).isFalse();
        assertThat(headers.containsHeader("X-User-Roles")).isFalse();
        assertThat(exchange.<String>getAttribute(AccessLogFilter.USER_ATTRIBUTE)).isEqualTo("service-account-1");
    }

    private void runAs(Jwt jwt, ServerWebExchange exchange) {
        filter.filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(new JwtAuthenticationToken(jwt)))
                .block();
    }

    private static Jwt ana() {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("3f0c2a2e-ana")
                .claim("preferred_username", "ana")
                .claim("name", "Ana Souza")
                .claim("email", "ana@exemplo.com")
                .claim("realm_access", Map.of("roles", List.of("customer")))
                .build();
    }
}
