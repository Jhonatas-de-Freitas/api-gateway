package io.github.jhonatasdefreitas.gateway;

import java.net.URI;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gateway")
public record GatewaySettings(Services services, RateLimit rateLimit, Cors cors) {

    public record Services(URI order, URI inventory, URI payment, URI notification, URI analytics) {
    }

    public record RateLimit(Bucket general, Bucket orderCreation) {
    }

    /** Token bucket: {@code replenishRate} tokens per second, holding at most {@code burstCapacity}. */
    public record Bucket(int replenishRate, int burstCapacity) {
    }

    public record Cors(List<String> allowedOrigins) {
    }
}
