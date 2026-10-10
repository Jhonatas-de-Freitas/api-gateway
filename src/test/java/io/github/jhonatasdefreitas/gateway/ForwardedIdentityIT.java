package io.github.jhonatasdefreitas.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class ForwardedIdentityIT extends IntegrationTestSupport {

    private static final String UUID_PATTERN = "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}";

    @BeforeEach
    void serviceAnswersAnything() {
        backend.stubFor(any(anyUrl()).willReturn(okJson("{}")));
    }

    @Test
    void tellsTheServiceWhoIsCalling() {
        TestUser ana = TestUser.ana();

        client.get().uri("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, bearer(ana))
                .exchange()
                .expectStatus().isOk();

        backend.verify(getRequestedFor(urlEqualTo("/orders"))
                .withHeader("X-User-Id", equalTo(ana.id()))
                .withHeader("X-User-Name", equalTo("Ana Souza"))
                .withHeader("X-User-Email", equalTo("ana@exemplo.com"))
                .withHeader("X-User-Roles", equalTo("customer")));
    }

    @Test
    void sendsEveryRealmRoleOfTheUser() {
        client.get().uri("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, bearer(TestUser.admin()))
                .exchange()
                .expectStatus().isOk();

        backend.verify(getRequestedFor(urlEqualTo("/orders")).withHeader("X-User-Roles", equalTo("admin,customer")));
    }

    @Test
    void replacesUserHeadersSentByTheCaller() {
        TestUser ana = TestUser.ana();

        client.get().uri("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, bearer(ana))
                .header("X-User-Id", "someone-else")
                .header("X-User-Name", "Admin Loja")
                .header("X-User-Email", "admin@exemplo.com")
                .header("X-User-Roles", "admin")
                .exchange()
                .expectStatus().isOk();

        backend.verify(getRequestedFor(urlEqualTo("/orders"))
                .withHeader("X-User-Id", equalTo(ana.id()))
                .withHeader("X-User-Name", equalTo("Ana Souza"))
                .withHeader("X-User-Email", equalTo("ana@exemplo.com"))
                .withHeader("X-User-Roles", equalTo("customer")));
    }

    @Test
    void dropsUserHeadersOnRoutesWithoutLogin() {
        client.get().uri("/api/products")
                .header("X-User-Id", "someone-else")
                .header("X-User-Roles", "admin")
                .exchange()
                .expectStatus().isOk();

        backend.verify(getRequestedFor(urlEqualTo("/products"))
                .withoutHeader("X-User-Id")
                .withoutHeader("X-User-Name")
                .withoutHeader("X-User-Email")
                .withoutHeader("X-User-Roles"));
    }

    @Test
    void createsACorrelationIdWhenNoneComes() {
        String returned = client.get().uri("/api/products")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches(CORRELATION_ID, UUID_PATTERN)
                .returnResult(Void.class)
                .getResponseHeaders().getFirst(CORRELATION_ID);

        backend.verify(getRequestedFor(urlEqualTo("/products")).withHeader(CORRELATION_ID, equalTo(returned)));
    }

    @Test
    void keepsTheCorrelationIdOfTheCaller() {
        client.get().uri("/api/products")
                .header(CORRELATION_ID, "checkout-42")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(CORRELATION_ID, "checkout-42");

        backend.verify(getRequestedFor(urlEqualTo("/products")).withHeader(CORRELATION_ID, equalTo("checkout-42")));
    }

    @Test
    void returnsOneCorrelationIdEvenWhenTheServiceEchoesIt() {
        backend.stubFor(any(urlEqualTo("/products"))
                .willReturn(okJson("[]").withHeader(CORRELATION_ID, "from-the-service")));

        client.get().uri("/api/products")
                .header(CORRELATION_ID, "checkout-42")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(CORRELATION_ID, "checkout-42");
    }

    @Test
    void replacesACorrelationIdThatIsNotAPlainToken() {
        client.get().uri("/api/products")
                .header(CORRELATION_ID, "id with spaces <script>")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches(CORRELATION_ID, UUID_PATTERN);

        backend.verify(getRequestedFor(urlEqualTo("/products")).withHeader(CORRELATION_ID, matching(UUID_PATTERN)));
    }

    @Test
    void refusedRequestsCarryTheCorrelationIdToo() {
        String returned = client.get().uri("/api/orders")
                .header(CORRELATION_ID, "checkout-42")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED)
                .returnResult(Void.class)
                .getResponseHeaders().getFirst(CORRELATION_ID);

        assertThat(returned).isEqualTo("checkout-42");
    }
}
