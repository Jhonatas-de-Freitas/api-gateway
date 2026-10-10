package io.github.jhonatasdefreitas.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.security.Principal;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

class UserOrIpKeyResolverTest {

    private final UserOrIpKeyResolver resolver = new UserOrIpKeyResolver();

    @Test
    void loggedInUserHasTheirOwnBucketWhateverTheAddress() {
        Principal ana = () -> "3f0c2a2e-ana";
        ServerWebExchange fromHome = requestFrom("203.0.113.7").mutate().principal(Mono.just(ana)).build();
        ServerWebExchange fromWork = requestFrom("198.51.100.20").mutate().principal(Mono.just(ana)).build();

        assertThat(resolver.resolve(fromHome).block()).isEqualTo("user:3f0c2a2e-ana");
        assertThat(resolver.resolve(fromWork).block()).isEqualTo("user:3f0c2a2e-ana");
    }

    @Test
    void callersWithoutLoginAreCountedByAddress() {
        assertThat(resolver.resolve(requestFrom("203.0.113.7")).block()).isEqualTo("ip:203.0.113.7");
        assertThat(resolver.resolve(requestFrom("198.51.100.20")).block()).isEqualTo("ip:198.51.100.20");
    }

    @Test
    void stillAnswersWhenTheAddressIsUnknown() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/products"));

        assertThat(resolver.resolve(exchange).block()).isEqualTo("ip:unknown");
    }

    private static MockServerWebExchange requestFrom(String address) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/products")
                .remoteAddress(new InetSocketAddress(address, 50000)));
    }
}
