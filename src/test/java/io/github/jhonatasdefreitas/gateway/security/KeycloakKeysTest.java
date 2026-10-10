package io.github.jhonatasdefreitas.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import reactor.core.publisher.Mono;

class KeycloakKeysTest {

    private static final Duration REFRESH_INTERVAL = Duration.ofSeconds(30);

    private final AtomicInteger downloads = new AtomicInteger();

    private Instant now = Instant.parse("2026-10-10T14:00:00Z");

    private Mono<JWKSet> published = Mono.just(new JWKSet(key("first")));

    private final KeycloakKeys keys = new KeycloakKeys(
            Mono.defer(() -> {
                downloads.incrementAndGet();
                return published;
            }),
            REFRESH_INTERVAL,
            () -> now);

    @Test
    void downloadsTheKeysOnceAndKeepsThem() {
        assertThat(keyIdsFor("first")).containsExactly("first");
        assertThat(keyIdsFor("first")).containsExactly("first");

        assertThat(downloads).hasValue(1);
    }

    @Test
    void unknownKeyIdDoesNotDownloadAgainInsideTheInterval() {
        keyIdsFor("first");

        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(keyIdsFor("made-up-" + attempt)).isEmpty();
        }

        assertThat(downloads).hasValue(1);
    }

    @Test
    void findsARotatedKeyOnceTheIntervalHasPassed() {
        keyIdsFor("first");
        published = Mono.just(new JWKSet(List.of(key("first"), key("second"))));

        assertThat(keyIdsFor("second")).isEmpty();

        now = now.plus(REFRESH_INTERVAL);

        assertThat(keyIdsFor("second")).containsExactly("second");
        assertThat(keyIdsFor("first")).containsExactly("first");
        assertThat(downloads).hasValue(2);
    }

    @Test
    void keepsTheKnownKeysWhenARefreshFails() {
        keyIdsFor("first");
        published = Mono.error(new IOException("Connection refused"));
        now = now.plus(REFRESH_INTERVAL);

        assertThatThrownBy(() -> keyIdsFor("second")).hasRootCauseInstanceOf(IOException.class);

        assertThat(keyIdsFor("first")).containsExactly("first");
    }

    @Test
    void triesAgainRightAfterADownloadThatFailed() {
        Mono<JWKSet> working = published;
        published = Mono.error(new IOException("Connection refused"));
        assertThatThrownBy(() -> keyIdsFor("first")).hasRootCauseInstanceOf(IOException.class);

        published = working;

        assertThat(keyIdsFor("first")).containsExactly("first");
    }

    private List<String> keyIdsFor(String keyId) {
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(),
                new JWTClaimsSet.Builder().build());
        return keys.apply(token).map(JWK::getKeyID).collectList().block();
    }

    // The kind of key does not matter here, only its id.
    private static JWK key(String keyId) {
        return new OctetSequenceKey.Builder(Base64URL.encode(keyId)).keyID(keyId).build();
    }
}
