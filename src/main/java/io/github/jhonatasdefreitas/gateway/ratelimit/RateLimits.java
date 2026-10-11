package io.github.jhonatasdefreitas.gateway.ratelimit;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.jhonatasdefreitas.gateway.GatewaySettings;
import io.github.jhonatasdefreitas.gateway.GatewaySettings.Bucket;
import io.github.jhonatasdefreitas.gateway.error.Problems;

@Configuration(proxyBeanMethods = false)
public class RateLimits {

    private static final String GENERAL = "general";
    private static final String ORDER_CREATION = "order-creation";

    // The limiter keeps one configuration per name. The name is also part of the Redis key, so the two
    // buckets of the same user never mix.
    @Bean
    RedisRateLimiter redisRateLimiter(GatewaySettings settings) {
        Bucket general = settings.rateLimit().general();
        Bucket orderCreation = settings.rateLimit().orderCreation();

        RedisRateLimiter limiter = new RedisRateLimiter(general.replenishRate(), general.burstCapacity());
        limiter.getConfig().put(ORDER_CREATION, new RedisRateLimiter.Config()
                .setReplenishRate(orderCreation.replenishRate())
                .setBurstCapacity(orderCreation.burstCapacity()));
        return limiter;
    }

    @Bean
    RateLimitFilter generalRateLimit(RedisRateLimiter limiter, KeyResolver keyResolver, Problems problems) {
        return new RateLimitFilter(GENERAL, limiter, keyResolver, problems);
    }

    @Bean
    RateLimitFilter orderCreationRateLimit(RedisRateLimiter limiter, KeyResolver keyResolver, Problems problems) {
        return new RateLimitFilter(ORDER_CREATION, limiter, keyResolver, problems);
    }
}
