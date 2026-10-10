package io.github.jhonatasdefreitas.gateway.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Keycloak puts realm roles in {@code realm_access.roles}, not in the {@code scope} claim Spring reads by default.
 */
final class KeycloakRealmRoles {

    private KeycloakRealmRoles() {
    }

    static List<String> of(Jwt jwt) {
        if (jwt.getClaim("realm_access") instanceof Map<?, ?> realmAccess
                && realmAccess.get("roles") instanceof Collection<?> roles) {
            return roles.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }
}
