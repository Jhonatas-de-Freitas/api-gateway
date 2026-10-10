package io.github.jhonatasdefreitas.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class KeycloakIT {

    private static final String KEYBOARD_STOCK = "/products/11111111-1111-1111-1111-111111111111/stock";

    // Reached through a random port while it says its public address is localhost:8180. That is the
    // situation of the gateway inside Docker: keys fetched from one address, tokens issued in the name
    // of another.
    @Container
    static final GenericContainer<?> keycloak = new GenericContainer<>("quay.io/keycloak/keycloak:26.8.0")
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_HOSTNAME", "http://localhost:8180")
            .withEnv("KC_HOSTNAME_BACKCHANNEL_DYNAMIC", "true")
            .withCopyFileToContainer(MountableFile.forHostPath("docker/keycloak/store-realm.json"),
                    "/opt/keycloak/data/import/store-realm.json")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/store").forPort(8080).withStartupTimeout(Duration.ofMinutes(3)));

    static final WireMockServer backend = new WireMockServer(options().dynamicPort());

    static {
        backend.start();
    }

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        registry.add("gateway.services.order", backend::baseUrl);
        registry.add("gateway.services.inventory", backend::baseUrl);
        registry.add("gateway.services.analytics", backend::baseUrl);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> keycloakUrl() + "/realms/store/protocol/openid-connect/certs");
    }

    @AfterAll
    static void stopBackend() {
        backend.stop();
    }

    @LocalServerPort
    int port;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        backend.resetAll();
        backend.stubFor(any(anyUrl()).willReturn(okJson("{}")));
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Test
    void issuesTokensInTheNameOfThePublicAddress() {
        String token = login("ana", "demo123");
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);

        assertThat(JsonPath.<String>read(payload, "$.iss")).isEqualTo("http://localhost:8180/realms/store");
        assertThat(JsonPath.<String>read(payload, "$.azp")).isEqualTo("store-web");
    }

    @Test
    void forwardsTheProfileOfAna() {
        client.get().uri("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + login("ana", "demo123"))
                .exchange()
                .expectStatus().isOk();

        backend.verify(getRequestedFor(urlEqualTo("/orders"))
                .withHeader("X-User-Id", matching("[0-9a-f-]{36}"))
                .withHeader("X-User-Name", equalTo("Ana Souza"))
                .withHeader("X-User-Email", equalTo("ana@exemplo.com"))
                .withHeader("X-User-Roles", matching("(.+,)?customer(,.+)?")));
    }

    @Test
    void anaIsACustomerAndNotAnAdmin() {
        String ana = "Bearer " + login("ana", "demo123");

        client.put().uri("/api" + KEYBOARD_STOCK)
                .header(HttpHeaders.AUTHORIZATION, ana)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"availableQuantity\": 50}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);

        client.get().uri("/api/analytics/summary")
                .header(HttpHeaders.AUTHORIZATION, ana)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminHasBothRoles() {
        String admin = "Bearer " + login("admin", "admin123");

        client.put().uri("/api" + KEYBOARD_STOCK)
                .header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"availableQuantity\": 50}")
                .exchange()
                .expectStatus().isOk();

        client.get().uri("/api/analytics/summary")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .exchange()
                .expectStatus().isOk();

        backend.verify(getRequestedFor(urlEqualTo("/analytics/summary"))
                .withHeader("X-User-Name", equalTo("Admin Loja"))
                .withHeader("X-User-Email", equalTo("admin@exemplo.com"))
                .withHeader("X-User-Roles", matching("(?=.*\\badmin\\b)(?=.*\\bcustomer\\b).*")));
    }

    // The direct access grant of the contract: user and password straight to the token endpoint.
    private static String login(String username, String password) {
        Map<?, ?> response = WebClient.create(keycloakUrl()).post()
                .uri("/realms/store/protocol/openid-connect/token")
                .body(passwordGrant(username, password))
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(30));
        return (String) response.get("access_token");
    }

    private static BodyInserters.FormInserter<String> passwordGrant(String username, String password) {
        return BodyInserters.fromFormData("grant_type", "password")
                .with("client_id", "store-web")
                .with("username", username)
                .with("password", password);
    }

    private static String keycloakUrl() {
        return "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
    }
}
