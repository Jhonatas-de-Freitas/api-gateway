package io.github.jhonatasdefreitas.gateway.security;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import io.github.jhonatasdefreitas.gateway.GatewaySettings;
import io.github.jhonatasdefreitas.gateway.error.Problems;
import io.github.jhonatasdefreitas.gateway.logging.CorrelationIdFilter;
import reactor.core.publisher.Flux;

@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
class SecurityConfig {

    private static final String ADMIN = "admin";

    @Bean
    SecurityWebFilterChain securityFilterChain(ServerHttpSecurity http, GatewaySettings settings, Problems problems) {
        ServerAuthenticationEntryPoint unauthorized = (exchange, failure) -> {
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            String detail = failure instanceof OAuth2AuthenticationException
                    ? "O token de acesso é inválido ou está vencido."
                    : "Envie um token de acesso no header Authorization.";
            return problems.write(exchange, HttpStatus.UNAUTHORIZED, detail);
        };
        ServerAccessDeniedHandler forbidden = (exchange, denied) -> problems.write(exchange, HttpStatus.FORBIDDEN,
                "Seu usuário não tem permissão para esta operação.");

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .cors(ServerHttpSecurity.CorsSpec::disable)
                .addFilterAt(new CorsFilter(corsConfigurationSource(settings.cors()), problems),
                        SecurityWebFiltersOrder.CORS)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.GET, "/api/products/**").permitAll()
                        .pathMatchers("/api/products/**", "/api/analytics/**").hasRole(ADMIN)
                        .pathMatchers("/api/orders/**", "/api/reservations/**", "/api/payments/**",
                                "/api/notifications/**", "/api/preferences/**").authenticated()
                        .pathMatchers(HttpMethod.GET, "/actuator/health/**", "/actuator/prometheus").permitAll()
                        // Anything not listed in the contract stays closed, including the internal fallback path.
                        .anyExchange().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(realmRolesConverter())))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                // After authentication so the token is known, before authorization so a 403 is logged with its user.
                .addFilterAfter(new UserHeadersFilter(), SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(OAuth2ResourceServerProperties properties) {
        OAuth2ResourceServerProperties.Jwt jwt = properties.getJwt();
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSource(KeycloakKeys.at(jwt.getJwkSetUri()))
                .build();
        // The default only checks "exp" when the token has one. Without it a token would never expire, and
        // without "sub" a service would get a request from nobody.
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(
                new JwtIssuerValidator(jwt.getIssuerUri()),
                required(JwtClaimNames.EXP),
                required(JwtClaimNames.SUB)));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> required(String claim) {
        return new JwtClaimValidator<Object>(claim, Objects::nonNull);
    }

    private static ReactiveJwtAuthenticationConverter realmRolesConverter() {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> Flux.fromIterable(KeycloakRealmRoles.of(jwt))
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role)));
        return converter;
    }

    private static CorsConfigurationSource corsConfigurationSource(GatewaySettings.Cors cors) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(cors.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE,
                HttpHeaders.ACCEPT, HttpHeaders.CACHE_CONTROL, "Last-Event-ID", "Idempotency-Key",
                CorrelationIdFilter.HEADER));
        configuration.setExposedHeaders(List.of(HttpHeaders.LOCATION, HttpHeaders.RETRY_AFTER,
                CorrelationIdFilter.HEADER, "X-RateLimit-Remaining", "X-RateLimit-Burst-Capacity",
                "X-RateLimit-Replenish-Rate"));
        configuration.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
