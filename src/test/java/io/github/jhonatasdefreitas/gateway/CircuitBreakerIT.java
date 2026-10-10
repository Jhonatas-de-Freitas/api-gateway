package io.github.jhonatasdefreitas.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.github.tomakehurst.wiremock.stubbing.Scenario;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * The test context runs with a 1 s time limit per call, and an open circuit lets a trial call through
 * after 1 s.
 */
class CircuitBreakerIT extends IntegrationTestSupport {

    private static final String PAYMENT = "/api/payments/7c1f0a34-5b1d-4f6e-8a2b-9d3e4f5a6b7c";

    private final String ana = bearer(TestUser.ana());

    @AfterEach
    void bringServicesBack() {
        if (!paymentService.isRunning()) {
            paymentService.start();
        }
        if (!backend.isRunning()) {
            backend.start();
        }
    }

    @Test
    void answersFastWhileTheServiceIsDownAndRecoversWhenItReturns() {
        paymentService.stop();

        for (int call = 0; call < 5; call++) {
            long startedAt = System.nanoTime();
            expectProblem(call(PAYMENT), HttpStatus.SERVICE_UNAVAILABLE, PAYMENT);
            assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(3));
        }
        assertThat(paymentBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        paymentService.start();
        paymentService.stubFor(any(anyUrl()).willReturn(okJson("{}")));

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    call(PAYMENT).expectStatus().isOk();
                    assertThat(paymentBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
                });
    }

    @Test
    void openCircuitAnswersWithoutCallingTheService() {
        paymentService.stubFor(any(anyUrl()).willReturn(okJson("{}")));
        call(PAYMENT).expectStatus().isOk();
        paymentService.resetRequests();

        paymentBreaker().transitionToOpenState();

        expectProblem(call(PAYMENT), HttpStatus.SERVICE_UNAVAILABLE, PAYMENT);
        paymentService.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void oneServiceDownDoesNotTakeTheOthersWithIt() {
        backend.stubFor(any(anyUrl()).willReturn(okJson("{}")));
        paymentService.stop();

        for (int call = 0; call < 5; call++) {
            call(PAYMENT).expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        call("/api/orders").expectStatus().isOk();
        call("/api/products").expectStatus().isOk();
    }

    @Test
    void cutsACallThatTakesLongerThanTheTimeLimit() {
        backend.stubFor(get("/orders/slow").willReturn(okJson("{}").withFixedDelay(5000)));

        long startedAt = System.nanoTime();
        expectProblem(call("/api/orders/slow"), HttpStatus.SERVICE_UNAVAILABLE, "/api/orders/slow");

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                .isGreaterThan(Duration.ofMillis(900))
                .isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void retriesAGetThatFailedOnce() {
        backend.stubFor(get("/products").inScenario("hiccup")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(serviceUnavailable())
                .willSetStateTo("recovered"));
        backend.stubFor(get("/products").inScenario("hiccup")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson("[]")));

        call("/api/products").expectStatus().isOk();

        backend.verify(2, getRequestedFor(urlEqualTo("/products")));
    }

    @Test
    void neverRetriesAPost() {
        backend.stubFor(post("/notifications").willReturn(serviceUnavailable()));

        client.post().uri("/api/notifications")
                .header(HttpHeaders.AUTHORIZATION, ana)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        backend.verify(1, postRequestedFor(urlEqualTo("/notifications")));
    }

    @Test
    void streamRouteAlsoAnswers503WhenTheServiceIsDown() {
        backend.stop();

        expectProblem(call("/api/orders/stream"), HttpStatus.SERVICE_UNAVAILABLE, "/api/orders/stream");
    }

    private WebTestClient.ResponseSpec call(String path) {
        return client.get().uri(path)
                .header(HttpHeaders.AUTHORIZATION, ana)
                .exchange();
    }

    private CircuitBreaker paymentBreaker() {
        return circuitBreakers.circuitBreaker("payment-service");
    }
}
