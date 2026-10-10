package io.github.jhonatasdefreitas.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * The gateway on a real port, with a real Redis, in front of WireMock servers that play the services
 * behind it and the Keycloak key endpoint. Every subclass shares the same application context.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
abstract class IntegrationTestSupport {

    static final String CORRELATION_ID = "X-Correlation-Id";

    private static final String JWKS_PATH = "/realms/store/protocol/openid-connect/certs";

    // Fixed ports, so a server that a test takes down comes back at the same address.
    static final WireMockServer backend = new WireMockServer(options().port(TestSocketUtils.findAvailableTcpPort()));
    static final WireMockServer paymentService = new WireMockServer(
            options().port(TestSocketUtils.findAvailableTcpPort()));
    static final WireMockServer keycloak = new WireMockServer(options().dynamicPort());

    static final TestTokens tokens = new TestTokens();

    static {
        backend.start();
        paymentService.start();
        keycloak.start();
        keycloak.stubFor(get(JWKS_PATH).willReturn(okJson(tokens.jwks())));
    }

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        registry.add("gateway.services.order", backend::baseUrl);
        registry.add("gateway.services.inventory", backend::baseUrl);
        registry.add("gateway.services.notification", backend::baseUrl);
        registry.add("gateway.services.analytics", backend::baseUrl);
        registry.add("gateway.services.payment", paymentService::baseUrl);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> keycloak.baseUrl() + JWKS_PATH);
        registry.add("resilience4j.timelimiter.configs.default.timeout-duration", () -> "1s");
        registry.add("resilience4j.circuitbreaker.configs.default.wait-duration-in-open-state", () -> "1s");
    }

    @LocalServerPort
    int port;

    @Autowired
    CircuitBreakerRegistry circuitBreakers;

    WebTestClient client;

    @BeforeEach
    void startFromACleanState() {
        backend.resetAll();
        paymentService.resetAll();
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(15))
                .build();
    }

    static String bearer(TestUser user) {
        return "Bearer " + tokens.tokenFor(user);
    }

    static void expectProblem(WebTestClient.ResponseSpec response, HttpStatus status, String path) {
        expectProblem(response.expectBody(String.class).returnResult(), status, path);
    }

    static void expectProblem(EntityExchangeResult<String> result, HttpStatus status, String path) {
        assertThat(result.getStatus()).isEqualTo(status);
        assertThat(result.getResponseHeaders().getContentType())
                .matches(MediaType.APPLICATION_PROBLEM_JSON::isCompatibleWith);
        assertThat(result.getResponseHeaders().getFirst(CORRELATION_ID)).isNotBlank();

        DocumentContext problem = JsonPath.parse(result.getResponseBody());
        assertThat(problem.read("$.type", String.class)).isEqualTo("about:blank");
        assertThat(problem.read("$.title", String.class)).isNotBlank();
        assertThat(problem.read("$.status", Integer.class)).isEqualTo(status.value());
        assertThat(problem.read("$.detail", String.class)).isNotBlank();
        assertThat(problem.read("$.instance", String.class)).isEqualTo(path);
    }
}
