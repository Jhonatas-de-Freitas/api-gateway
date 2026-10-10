package io.github.jhonatasdefreitas.gateway.ratelimit;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;

import io.github.jhonatasdefreitas.gateway.error.Problems;
import reactor.core.publisher.Mono;

/**
 * Same token bucket as the gateway's own {@code RequestRateLimiter} filter. That one answers a refused
 * request with an empty body, and here it has to be a problem+json.
 */
public class RateLimitFilter implements GatewayFilter {

    private final String bucket;
    private final RedisRateLimiter limiter;
    private final KeyResolver keyResolver;
    private final Problems problems;

    RateLimitFilter(String bucket, RedisRateLimiter limiter, KeyResolver keyResolver, Problems problems) {
        this.bucket = bucket;
        this.limiter = limiter;
        this.keyResolver = keyResolver;
        this.problems = problems;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return keyResolver.resolve(exchange)
                .flatMap(key -> limiter.isAllowed(bucket, key))
                .flatMap(result -> {
                    HttpHeaders headers = exchange.getResponse().getHeaders();
                    result.getHeaders().forEach(headers::set);
                    if (result.isAllowed()) {
                        return chain.filter(exchange);
                    }
                    // The bucket refills every second, so that is the longest useful wait.
                    headers.set(HttpHeaders.RETRY_AFTER, "1");
                    return problems.write(exchange, HttpStatus.TOO_MANY_REQUESTS,
                            "Limite de requisições atingido. Aguarde um instante e tente de novo.");
                });
    }
}
