package io.github.jhonatasdefreitas.gateway;

import static org.springframework.cloud.gateway.support.RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR;

import java.time.Duration;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.GatewayFilterSpec;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import io.github.jhonatasdefreitas.gateway.GatewaySettings.Services;
import io.github.jhonatasdefreitas.gateway.error.ServiceFallback;
import io.github.jhonatasdefreitas.gateway.ratelimit.RateLimitFilter;

@Configuration(proxyBeanMethods = false)
class Routes {

    private static final String ORDER_SERVICE = "order-service";
    private static final String INVENTORY_SERVICE = "inventory-service";
    private static final String PAYMENT_SERVICE = "payment-service";
    private static final String NOTIFICATION_SERVICE = "notification-service";
    private static final String ORDER_ANALYTICS = "order-analytics";

    // Who may call each path is decided before this, in SecurityConfig.
    @Bean
    RouteLocator routeLocator(RouteLocatorBuilder builder, GatewaySettings settings,
            RateLimitFilter generalRateLimit, RateLimitFilter orderCreationRateLimit) {
        Services services = settings.services();
        return builder.routes()
                // The breaker's time limit counts until the response starts, and a stream may stay silent
                // until its first event. So this route has no breaker and no response timeout; when the
                // service is down the 503 comes from GatewayErrorHandler instead of the fallback.
                .route("orders-stream", route -> route.path("/api/orders/stream")
                        .filters(filters -> filters.stripPrefix(1).filter(generalRateLimit))
                        .metadata(RESPONSE_TIMEOUT_ATTR, -1)
                        .uri(services.order()))
                .route("orders-create", route -> route.path("/api/orders", "/api/orders/").and().method(HttpMethod.POST)
                        .filters(filters -> guarded(filters, ORDER_SERVICE, orderCreationRateLimit))
                        .uri(services.order()))
                .route("orders", route -> route.path("/api/orders/**")
                        .filters(filters -> guarded(filters, ORDER_SERVICE, generalRateLimit))
                        .uri(services.order()))
                .route("inventory", route -> route.path("/api/products/**", "/api/reservations/**")
                        .filters(filters -> guarded(filters, INVENTORY_SERVICE, generalRateLimit))
                        .uri(services.inventory()))
                .route("payments", route -> route.path("/api/payments/**")
                        .filters(filters -> guarded(filters, PAYMENT_SERVICE, generalRateLimit))
                        .uri(services.payment()))
                .route("notifications", route -> route.path("/api/notifications/**", "/api/preferences/**")
                        .filters(filters -> guarded(filters, NOTIFICATION_SERVICE, generalRateLimit))
                        .uri(services.notification()))
                .route("analytics", route -> route.path("/api/analytics/**")
                        .filters(filters -> guarded(filters, ORDER_ANALYTICS, generalRateLimit))
                        .uri(services.analytics()))
                .build();
    }

    // The order matters: a refused request never reaches the breaker, and the retries of a GET run
    // inside the breaker's time limit, so they count as a single call.
    private static GatewayFilterSpec guarded(GatewayFilterSpec filters, String service, GatewayFilter rateLimit) {
        return filters.stripPrefix(1)
                // The services do not know about /api: a Location they answer gets the prefix back.
                .rewriteResponseHeader(HttpHeaders.LOCATION, "^/", "/api/")
                .filter(rateLimit)
                .circuitBreaker(breaker -> breaker.setName(service).setFallbackUri(ServiceFallback.uriFor(service)))
                .retry(retry -> retry.setRetries(2)
                        .setMethods(HttpMethod.GET)
                        .setSeries()
                        .setStatuses(HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE,
                                HttpStatus.GATEWAY_TIMEOUT)
                        .setBackoff(Duration.ofMillis(50), Duration.ofMillis(500), 2, true));
    }
}
