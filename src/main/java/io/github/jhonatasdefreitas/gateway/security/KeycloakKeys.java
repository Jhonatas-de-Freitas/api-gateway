package io.github.jhonatasdefreitas.gateway.security;

import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.function.Function;

import org.springframework.web.reactive.function.client.WebClient;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The public keys of Keycloak, kept in memory. A token signed with a key that is not here may mean the
 * keys were rotated, so they are downloaded again, but at most once per interval. Spring's own key source
 * downloads on every unknown key id, which turns each request with a made-up id into a call to Keycloak.
 */
final class KeycloakKeys implements Function<SignedJWT, Flux<JWK>> {

    private static final Duration FOREVER = Duration.ofMillis(Long.MAX_VALUE);

    private final Mono<JWKSet> download;
    private final Duration refreshInterval;
    private final InstantSource clock;

    private volatile JWKSet known = new JWKSet();
    private Mono<JWKSet> refresh = Mono.empty();
    private Instant nextRefresh = Instant.MIN;

    KeycloakKeys(Mono<JWKSet> download, Duration refreshInterval, InstantSource clock) {
        this.download = download;
        this.refreshInterval = refreshInterval;
        this.clock = clock;
    }

    static KeycloakKeys at(String jwkSetUri) {
        Mono<JWKSet> download = WebClient.create().get().uri(jwkSetUri)
                .retrieve()
                .bodyToMono(String.class)
                .map(KeycloakKeys::parse)
                .timeout(Duration.ofSeconds(5));
        return new KeycloakKeys(download, Duration.ofSeconds(30), InstantSource.system());
    }

    @Override
    public Flux<JWK> apply(SignedJWT token) {
        String keyId = token.getHeader().getKeyID();
        JWK key = known.getKeyByKeyId(keyId);
        if (key != null) {
            return Flux.just(key);
        }
        return refreshed().mapNotNull(keys -> keys.getKeyByKeyId(keyId)).flux();
    }

    // Requests that arrive during a download wait for that same download. One that fails is not kept:
    // the keys already known stay valid and the next request tries again.
    private synchronized Mono<JWKSet> refreshed() {
        Instant now = clock.instant();
        if (!now.isBefore(nextRefresh)) {
            nextRefresh = now.plus(refreshInterval);
            refresh = download.doOnNext(keys -> known = keys)
                    .cache(keys -> FOREVER, error -> Duration.ZERO, () -> Duration.ZERO);
        }
        return refresh;
    }

    private static JWKSet parse(String json) {
        try {
            return JWKSet.parse(json);
        }
        catch (ParseException e) {
            throw new IllegalStateException("Keycloak answered with something that is not a key set", e);
        }
    }
}
