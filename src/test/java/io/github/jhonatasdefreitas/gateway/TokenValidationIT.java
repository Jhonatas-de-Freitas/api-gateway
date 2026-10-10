package io.github.jhonatasdefreitas.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.lessThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import java.time.Instant;
import java.util.Date;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class TokenValidationIT extends IntegrationTestSupport {

    @BeforeEach
    void servicesAreUp() {
        servicesAnswerAnything();
    }

    @AfterEach
    void nothingReachedTheServices() {
        verifyNoServiceWasCalled();
    }

    @Test
    void refusesSomethingThatIsNotAToken() {
        expectUnauthorized("Bearer not-a-token");
    }

    @Test
    void refusesAnExpiredToken() {
        Instant anHourAgo = Instant.now().minusSeconds(3600);
        String expired = tokens.tokenFor(TestUser.ana(), claims -> claims
                .issueTime(Date.from(anHourAgo.minusSeconds(300)))
                .expirationTime(Date.from(anHourAgo)));

        expectUnauthorized("Bearer " + expired);
    }

    @Test
    void refusesATokenThatNeverExpires() {
        String eternal = tokens.tokenFor(TestUser.ana(), claims -> claims.expirationTime(null));

        expectUnauthorized("Bearer " + eternal);
    }

    @Test
    void refusesATokenWithoutSubject() {
        String nobody = tokens.tokenFor(TestUser.ana(), claims -> claims.subject(null));

        expectUnauthorized("Bearer " + nobody);
    }

    @Test
    void refusesATokenSignedByAnotherKey() {
        String forged = tokens.withForgedKey().tokenFor(TestUser.admin());

        expectUnauthorized("Bearer " + forged);
    }

    @Test
    void refusesATokenFromAnotherIssuer() {
        String otherRealm = tokens.tokenFor(TestUser.ana(),
                claims -> claims.issuer("http://localhost:8180/realms/other"));

        expectUnauthorized("Bearer " + otherRealm);
    }

    @Test
    void refusesATokenChangedAfterSigning() {
        String[] parts = tokens.tokenFor(TestUser.ana()).split("\\.");
        String asAdmin = tokens.tokenFor(TestUser.admin()).split("\\.")[1];

        expectUnauthorized("Bearer " + parts[0] + "." + asAdmin + "." + parts[2]);
    }

    @Test
    void madeUpKeyIdsDoNotBecomeCallsToKeycloak() {
        keycloak.resetRequests();

        for (int attempt = 1; attempt <= 10; attempt++) {
            String stranger = tokens.withKeyId("made-up-" + attempt).tokenFor(TestUser.admin());
            expectUnauthorized("Bearer " + stranger);
        }

        keycloak.verify(lessThanOrExactly(1), getRequestedFor(urlEqualTo(JWKS_PATH)));
    }

    @Test
    void refusesBasicCredentials() {
        expectUnauthorized("Basic YW5hOmRlbW8xMjM=");
    }

    private void expectUnauthorized(String authorization) {
        expectProblem(client.get().uri("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .exchange(), HttpStatus.UNAUTHORIZED, "/api/orders");
    }
}
