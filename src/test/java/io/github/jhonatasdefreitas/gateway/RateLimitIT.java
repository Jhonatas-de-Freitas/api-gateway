package io.github.jhonatasdefreitas.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

// The limits here are the ones of the contract: 2 orders per second with a burst of 5, and 20 per second
// with a burst of 40 for everything else.
class RateLimitIT extends IntegrationTestSupport {

    private static final String ORDER = """
            { "customerId": "c-1001", "customerName": "Ana Souza", "customerEmail": "ana@exemplo.com",
              "items": [ { "productId": "11111111-1111-1111-1111-111111111111", "quantity": 2 } ] }
            """;

    @BeforeEach
    void serviceAcceptsOrders() {
        servicesAnswerAnything();
        orderService.stubFor(post("/orders").willReturn(aResponse().withStatus(202)
                .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .withBody("{ \"status\": \"PENDING\" }")));
    }

    @Test
    void cutsABurstOfOrdersAtTheLimit() {
        String ana = bearer(TestUser.ana());

        int accepted = 0;
        EntityExchangeResult<String> refused = null;
        // The bucket gets 2 tokens back whenever the clock moves to the next second, so the burst may go
        // a little beyond 5 before it is refused.
        for (int attempt = 0; attempt < 12 && refused == null; attempt++) {
            EntityExchangeResult<String> result = placeOrder(ana);
            if (result.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
                refused = result;
            }
            else {
                assertThat(result.getStatus()).isEqualTo(HttpStatus.ACCEPTED);
                accepted++;
            }
        }

        assertThat(accepted).isBetween(5, 9);
        assertThat(refused).isNotNull();
        expectProblem(refused, HttpStatus.TOO_MANY_REQUESTS, "/api/orders");
        HttpHeaders headers = refused.getResponseHeaders();
        assertThat(headers.getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(headers.getFirst("X-RateLimit-Burst-Capacity")).isEqualTo("5");
        assertThat(headers.getFirst("X-RateLimit-Replenish-Rate")).isEqualTo("2");
        assertThat(headers.getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        orderService.verify(accepted, postRequestedFor(urlEqualTo("/orders")));
    }

    @Test
    void limitsEachUserOnTheirOwn() {
        exhaustOrderLimit(bearer(TestUser.ana()));

        assertThat(placeOrder(bearer(TestUser.admin())).getStatus()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void orderLimitDoesNotBlockTheOtherRoutes() {
        String ana = bearer(TestUser.ana());
        exhaustOrderLimit(ana);

        client.get().uri("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, ana)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-RateLimit-Burst-Capacity", "40")
                .expectHeader().valueEquals("X-RateLimit-Replenish-Rate", "20");
    }

    @Test
    void acceptsOrdersAgainOnceTheBucketRefills() {
        String ana = bearer(TestUser.ana());
        exhaustOrderLimit(ana);

        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> placeOrder(ana).getStatus() == HttpStatus.ACCEPTED);
    }

    @Test
    void appliesTheGeneralLimitToCallersWithoutLogin() {
        client.get().uri("/api/products")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-RateLimit-Remaining")
                .expectHeader().valueEquals("X-RateLimit-Burst-Capacity", "40")
                .expectHeader().valueEquals("X-RateLimit-Replenish-Rate", "20");
    }

    private void exhaustOrderLimit(String authorization) {
        for (int attempt = 0; attempt < 12; attempt++) {
            if (placeOrder(authorization).getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
                return;
            }
        }
        throw new AssertionError("12 orders in a row and none was refused");
    }

    private EntityExchangeResult<String> placeOrder(String authorization) {
        return client.post().uri("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ORDER)
                .exchange()
                .expectBody(String.class)
                .returnResult();
    }
}
