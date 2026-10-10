package io.github.jhonatasdefreitas.gateway.security;

import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import io.github.jhonatasdefreitas.gateway.logging.AccessLogFilter;
import reactor.core.publisher.Mono;

/**
 * Tells the services behind who is calling. The {@code X-User-*} headers are always rebuilt from the
 * validated token, so a caller cannot send them and pass for someone else.
 */
class UserHeadersFilter implements WebFilter {

    static final String USER_ID = "X-User-Id";
    static final String USER_NAME = "X-User-Name";
    static final String USER_EMAIL = "X-User-Email";
    static final String USER_ROLES = "X-User-Roles";

    private static final List<String> USER_HEADERS = List.of(USER_ID, USER_NAME, USER_EMAIL, USER_ROLES);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(authentication -> Optional.of(((JwtAuthenticationToken) authentication).getToken()))
                .defaultIfEmpty(Optional.empty())
                .flatMap(token -> {
                    token.map(UserHeadersFilter::displayName)
                            .ifPresent(user -> exchange.getAttributes().put(AccessLogFilter.USER_ATTRIBUTE, user));
                    return chain.filter(exchange.mutate()
                            .request(request -> request.headers(headers -> rewrite(headers, token)))
                            .build());
                });
    }

    private static void rewrite(HttpHeaders headers, Optional<Jwt> token) {
        USER_HEADERS.forEach(headers::remove);
        token.ifPresent(jwt -> {
            setIfPresent(headers, USER_ID, jwt.getSubject());
            setIfPresent(headers, USER_NAME, jwt.getClaimAsString("name"));
            setIfPresent(headers, USER_EMAIL, jwt.getClaimAsString("email"));
            List<String> roles = KeycloakRealmRoles.of(jwt);
            if (!roles.isEmpty()) {
                headers.set(USER_ROLES, String.join(",", roles));
            }
        });
    }

    private static void setIfPresent(HttpHeaders headers, String name, String value) {
        if (value != null) {
            headers.set(name, value);
        }
    }

    private static String displayName(Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        return username != null ? username : jwt.getSubject();
    }
}
