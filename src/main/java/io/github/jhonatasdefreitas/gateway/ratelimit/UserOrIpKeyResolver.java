package io.github.jhonatasdefreitas.gateway.ratelimit;

import java.net.InetSocketAddress;
import java.security.Principal;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * One bucket per logged-in user. Requests without login share a bucket per client address.
 */
@Component
class UserOrIpKeyResolver implements KeyResolver {

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .map(Principal::getName)
                .map(user -> "user:" + user)
                .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + clientAddress(exchange)));
    }

    private static String clientAddress(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote != null && remote.getAddress() != null ? remote.getAddress().getHostAddress() : "unknown";
    }
}
