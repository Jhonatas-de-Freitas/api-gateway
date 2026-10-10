package io.github.jhonatasdefreitas.gateway;

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Stands in for Keycloak: holds a signing key and issues access tokens shaped like the ones of the realm.
 */
final class TestTokens {

    static final String ISSUER = "http://localhost:8180/realms/store";

    private final RSAKey key;

    TestTokens() {
        this("test-key");
    }

    private TestTokens(String keyId) {
        try {
            this.key = new RSAKeyGenerator(2048).keyID(keyId).generate();
        }
        catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Another key pair published under the same key id, as a forger would try. */
    TestTokens withForgedKey() {
        return new TestTokens(key.getKeyID());
    }

    String jwks() {
        return new JWKSet(key.toPublicJWK()).toString();
    }

    String tokenFor(TestUser user) {
        return tokenFor(user, claims -> {
        });
    }

    String tokenFor(TestUser user, Consumer<JWTClaimsSet.Builder> changes) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(user.id())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("azp", "store-web")
                .claim("preferred_username", user.username())
                .claim("name", user.name())
                .claim("email", user.email())
                .claim("realm_access", Map.of("roles", user.roles()));
        changes.accept(claims);

        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                claims.build());
        try {
            jwt.sign(new RSASSASigner(key));
        }
        catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }
}
