package io.github.jhonatasdefreitas.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class CorsIT extends IntegrationTestSupport {

    private static final String DASHBOARD = "http://localhost:5173";
    private static final String SOMEWHERE_ELSE = "http://localhost:3000";

    @BeforeEach
    void serviceAnswersAnything() {
        backend.stubFor(any(anyUrl()).willReturn(okJson("{}")));
    }

    @Test
    void acceptsThePreflightOfTheDashboard() {
        client.options().uri("/api/orders")
                .header(HttpHeaders.ORIGIN, DASHBOARD)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization, content-type, x-correlation-id")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, DASHBOARD)
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        methods -> assertThat(methods).contains("POST"))
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        headers -> assertThat(headers).containsIgnoringCase("authorization"));

        backend.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void refusesThePreflightOfAnotherOrigin() {
        client.options().uri("/api/orders")
                .header(HttpHeaders.ORIGIN, SOMEWHERE_ELSE)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN)
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    void letsTheDashboardReadTheAnswer() {
        client.get().uri("/api/products")
                .header(HttpHeaders.ORIGIN, DASHBOARD)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, DASHBOARD)
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        exposed -> assertThat(exposed).contains(CORRELATION_ID));
    }

    @Test
    void refusesARequestOfAnotherOriginBeforeItReachesTheService() {
        client.get().uri("/api/products")
                .header(HttpHeaders.ORIGIN, SOMEWHERE_ELSE)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN)
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);

        backend.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void errorsAlsoCarryTheCorsHeaders() {
        client.get().uri("/api/orders")
                .header(HttpHeaders.ORIGIN, DASHBOARD)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, DASHBOARD);
    }
}
