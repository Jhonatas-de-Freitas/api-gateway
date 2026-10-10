package io.github.jhonatasdefreitas.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.github.tomakehurst.wiremock.WireMockServer;

class AccessRulesIT extends IntegrationTestSupport {

    private static final String KEYBOARD = "11111111-1111-1111-1111-111111111111";
    private static final String ORDER = "7c1f0a34-5b1d-4f6e-8a2b-9d3e4f5a6b7c";

    @BeforeEach
    void servicesAreUp() {
        servicesAnswerAnything();
    }

    @Test
    void anyoneCanBrowseProducts() {
        client.get().uri("/api/products")
                .exchange()
                .expectStatus().isOk();

        client.get().uri("/api/products/" + KEYBOARD)
                .exchange()
                .expectStatus().isOk();

        inventoryService.verify(getRequestedFor(urlEqualTo("/products")));
        inventoryService.verify(getRequestedFor(urlEqualTo("/products/" + KEYBOARD)));
    }

    @Test
    void ordersNeedALogin() {
        expectProblem(client.get().uri("/api/orders").exchange(), HttpStatus.UNAUTHORIZED, "/api/orders");

        verifyNoServiceWasCalled();
    }

    @Test
    void customerReadsOrders() {
        client.get().uri("/api/orders?status=CONFIRMED&page=0&size=20")
                .header(HttpHeaders.AUTHORIZATION, bearer(TestUser.ana()))
                .exchange()
                .expectStatus().isOk();

        orderService.verify(getRequestedFor(urlEqualTo("/orders?status=CONFIRMED&page=0&size=20")));
    }

    @Test
    void customerCannotChangeStock() {
        expectProblem(client.put().uri("/api/products/" + KEYBOARD + "/stock")
                .header(HttpHeaders.AUTHORIZATION, bearer(TestUser.ana()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"availableQuantity\": 50}")
                .exchange(), HttpStatus.FORBIDDEN, "/api/products/" + KEYBOARD + "/stock");

        verifyNoServiceWasCalled();
    }

    @Test
    void adminChangesStock() {
        client.put().uri("/api/products/" + KEYBOARD + "/stock")
                .header(HttpHeaders.AUTHORIZATION, bearer(TestUser.admin()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"availableQuantity\": 50}")
                .exchange()
                .expectStatus().isOk();

        inventoryService.verify(putRequestedFor(urlEqualTo("/products/" + KEYBOARD + "/stock"))
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

        verifyNoServiceWasCalled();
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

        orderAnalytics.verify(1, getRequestedFor(urlEqualTo("/analytics/summary")));
    }

    static Stream<Arguments> loggedInRoutes() {
        return Stream.of(
                arguments("/api/orders/" + ORDER, named("order-service", orderService), "/orders/" + ORDER),
                arguments("/api/reservations/" + ORDER, named("inventory-service", inventoryService),
                        "/reservations/" + ORDER),
                arguments("/api/payments/" + ORDER, named("payment-service", paymentService), "/payments/" + ORDER),
                arguments("/api/notifications?userId=c-1001", named("notification-service", notificationService),
                        "/notifications?userId=c-1001"),
                arguments("/api/preferences/c-1001", named("notification-service", notificationService),
                        "/preferences/c-1001"));
    }

    @ParameterizedTest
    @MethodSource("loggedInRoutes")
    void loggedInRoutesReachTheirServiceWithoutTheApiPrefix(String gatewayPath, WireMockServer service,
            String servicePath) {
        expectProblem(client.get().uri(gatewayPath).exchange(), HttpStatus.UNAUTHORIZED,
                gatewayPath.replaceFirst("\\?.*", ""));

        client.get().uri(gatewayPath)
                .header(HttpHeaders.AUTHORIZATION, bearer(TestUser.ana()))
                .exchange()
                .expectStatus().isOk();

        service.verify(1, getRequestedFor(urlEqualTo(servicePath)));
        assertThat(services).filteredOn(other -> other != service)
                .allSatisfy(other -> other.verify(0, anyRequestedFor(anyUrl())));
    }

    @Test
    void locationOfANewOrderIsAPathOfTheGateway() {
        orderService.stubFor(post("/orders").willReturn(aResponse().withStatus(202)
                .withHeader(HttpHeaders.LOCATION, "/orders/" + ORDER)));

        String location = client.post().uri("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, bearer(TestUser.ana()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isAccepted()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/api/orders/" + ORDER)
                .returnResult(Void.class)
                .getResponseHeaders().getFirst(HttpHeaders.LOCATION);

        client.get().uri(location)
                .header(HttpHeaders.AUTHORIZATION, bearer(TestUser.ana()))
                .exchange()
                .expectStatus().isOk();
    }

    @ParameterizedTest
    @CsvSource({ "/orders", "/api/unknown", "/fallback/order-service", "/actuator/env" })
    void pathsOutsideTheContractStayClosed(String path) {
        expectProblem(client.get().uri(path).exchange(), HttpStatus.UNAUTHORIZED, path);

        expectProblem(client.get().uri(path)
                .header(HttpHeaders.AUTHORIZATION, bearer(TestUser.admin()))
                .exchange(), HttpStatus.FORBIDDEN, path);

        verifyNoServiceWasCalled();
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
