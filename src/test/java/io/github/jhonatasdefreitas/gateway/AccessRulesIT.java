package io.github.jhonatasdefreitas.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class AccessRulesIT extends IntegrationTestSupport {

    private static final String KEYBOARD = "11111111-1111-1111-1111-111111111111";

    @BeforeEach
    void servicesAnswerAnything() {
        backend.stubFor(any(anyUrl()).willReturn(okJson("{}")));
        paymentService.stubFor(any(anyUrl()).willReturn(okJson("{}")));
    }

    @Test
    void anyoneCanBrowseProducts() {
        client.get().uri("/api/products")
                .exchange()
                .expectStatus().isOk();

        client.get().uri("/api/products/" + KEYBOARD)
                .exchange()
                .expectStatus().isOk();

        backend.verify(getRequestedFor(urlEqualTo("/products")));
        backend.verify(getRequestedFor(urlEqualTo("/products/" + KEYBOARD)));
    }

    @Test
    void ordersNeedALogin() {
        expectProblem(client.get().uri("/api/orders").exchange(), HttpStatus.UNAUTHORIZED, "/api/orders");

        backend.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void customerReadsOrders() {
        client.get().uri("/api/orders?status=CONFIRMED&page=0&size=20")
                .header(HttpHeaders.AUTHORIZATION, bearer(TestUser.ana()))
                .exchange()
                .expectStatus().isOk();

        backend.verify(getRequestedFor(urlEqualTo("/orders?status=CONFIRMED&page=0&size=20")));
    }

    @Test
    void customerCannotChangeStock() {
        expectProblem(client.put().uri("/api/products/" + KEYBOARD + "/stock")
                .header(HttpHeaders.AUTHORIZATION, bearer(TestUser.ana()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"availableQuantity\": 50}")
                .exchange(), HttpStatus.FORBIDDEN, "/api/products/" + KEYBOARD + "/stock");

        backend.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void adminChangesStock() {
        client.put().uri("/api/products/" + KEYBOARD + "/stock")
                .header(HttpHeaders.AUTHORIZATION, bearer(TestUser.admin()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"availableQuantity\": 50}")
                .exchange()
                .expectStatus().isOk();

        backend.verify(putRequestedFor(urlEqualTo("/products/" + KEYBOARD + "/stock"))
                .withRequestBody(equalToJson("{\"availableQuantity\": 50}")));
    }

    @Test
    void creatingAProductNeedsAdmin() {
        expectProblem(client.post().uri("/api/products")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange(), HttpStatus.UNAUTHORIZED, "/api/products");

        expectProblem(client.post().uri("/api/products")
                .header(HttpHeaders.AUTHORIZATION, bearer(TestUser.ana()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange(), HttpStatus.FORBIDDEN, "/api/products");

        backend.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void analyticsIsForAdminOnly() {
        expectProblem(client.get().uri("/api/analytics/summary")
                .header(HttpHeaders.AUTHORIZATION, bearer(TestUser.ana()))
                .exchange(), HttpStatus.FORBIDDEN, "/api/analytics/summary");

        client.get().uri("/api/analytics/summary")
                .header(HttpHeaders.AUTHORIZATION, bearer(TestUser.admin()))
                .exchange()
                .expectStatus().isOk();

        backend.verify(1, getRequestedFor(urlEqualTo("/analytics/summary")));
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            /api/orders/7c1f0a34-5b1d-4f6e-8a2b-9d3e4f5a6b7c, /orders/7c1f0a34-5b1d-4f6e-8a2b-9d3e4f5a6b7c
            /api/reservations/7c1f0a34-5b1d-4f6e-8a2b-9d3e4f5a6b7c, /reservations/7c1f0a34-5b1d-4f6e-8a2b-9d3e4f5a6b7c
            /api/notifications?userId=c-1001, /notifications?userId=c-1001
            /api/preferences/c-1001, /preferences/c-1001
            """)
    void loggedInRoutesDropTheApiPrefix(String gatewayPath, String servicePath) {
        expectProblem(client.get().uri(gatewayPath).exchange(), HttpStatus.UNAUTHORIZED,
                gatewayPath.replaceFirst("\\?.*", ""));

        client.get().uri(gatewayPath)
                .header(HttpHeaders.AUTHORIZATION, bearer(TestUser.ana()))
                .exchange()
                .expectStatus().isOk();

        backend.verify(1, getRequestedFor(urlEqualTo(servicePath)));
    }

    @Test
    void paymentsGoToThePaymentService() {
        expectProblem(client.get().uri("/api/payments").exchange(), HttpStatus.UNAUTHORIZED, "/api/payments");

        client.get().uri("/api/payments/7c1f0a34-5b1d-4f6e-8a2b-9d3e4f5a6b7c")
                .header(HttpHeaders.AUTHORIZATION, bearer(TestUser.ana()))
                .exchange()
                .expectStatus().isOk();

        paymentService.verify(1, getRequestedFor(urlEqualTo("/payments/7c1f0a34-5b1d-4f6e-8a2b-9d3e4f5a6b7c")));
        backend.verify(0, anyRequestedFor(anyUrl()));
    }

    @ParameterizedTest
    @CsvSource({ "/orders", "/api/unknown", "/fallback/order-service", "/actuator/env" })
    void pathsOutsideTheContractStayClosed(String path) {
        expectProblem(client.get().uri(path).exchange(), HttpStatus.UNAUTHORIZED, path);

        expectProblem(client.get().uri(path)
                .header(HttpHeaders.AUTHORIZATION, bearer(TestUser.admin()))
                .exchange(), HttpStatus.FORBIDDEN, path);

        backend.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void healthAndMetricsAreOpen() {
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");

        client.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk();
    }
}
