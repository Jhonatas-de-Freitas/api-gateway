package io.github.jhonatasdefreitas.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

class CorsIT extends IntegrationTestSupport {

    private static final String DASHBOARD = "http://localhost:5173";
    private static final String SOMEWHERE_ELSE = "http://localhost:3000";

    @BeforeEach
    void servicesAreUp() {
        servicesAnswerAnything();
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

        verifyNoServiceWasCalled();
    }

    @Test
    void refusesThePreflightOfAnotherOrigin() {
        EntityExchangeResult<String> refused = client.options().uri("/api/orders")
                .header(HttpHeaders.ORIGIN, SOMEWHERE_ELSE)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)
                .expectBody(String.class)
                .returnResult();

        expectProblem(refused, HttpStatus.FORBIDDEN, "/api/orders");
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
        EntityExchangeResult<String> refused = client.get().uri("/api/products")
                .header(HttpHeaders.ORIGIN, SOMEWHERE_ELSE)
                .exchange()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)
                .expectBody(String.class)
                .returnResult();

        expectProblem(refused, HttpStatus.FORBIDDEN, "/api/products");
        verifyNoServiceWasCalled();
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
