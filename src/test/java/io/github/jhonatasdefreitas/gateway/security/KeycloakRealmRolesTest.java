package io.github.jhonatasdefreitas.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRealmRolesTest {

    @Test
    void readsTheRolesOfTheRealm() {
        Jwt jwt = jwtWith("realm_access", Map.of("roles", List.of("customer", "admin")));

        assertThat(KeycloakRealmRoles.of(jwt)).containsExactly("customer", "admin");
    }

    @Test
    void ignoresRolesOfOtherClients() {
        Jwt jwt = jwtWith("resource_access", Map.of("account", Map.of("roles", List.of("manage-account"))));

        assertThat(KeycloakRealmRoles.of(jwt)).isEmpty();
    }

    @Test
    void tokenWithoutRolesHasNone() {
        assertThat(KeycloakRealmRoles.of(jwtWith("scope", "openid profile"))).isEmpty();
        assertThat(KeycloakRealmRoles.of(jwtWith("realm_access", Map.of()))).isEmpty();
    }

    @Test
    void toleratesAClaimInAnUnexpectedShape() {
        assertThat(KeycloakRealmRoles.of(jwtWith("realm_access", "admin"))).isEmpty();
        assertThat(KeycloakRealmRoles.of(jwtWith("realm_access", Map.of("roles", "admin")))).isEmpty();
        assertThat(KeycloakRealmRoles.of(jwtWith("realm_access", Map.of("roles", List.of("customer", 42)))))
                .containsExactly("customer");
    }

    private static Jwt jwtWith(String claim, Object value) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("user-1")
                .claim(claim, value)
                .build();
    }
}
