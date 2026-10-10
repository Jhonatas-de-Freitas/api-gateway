package io.github.jhonatasdefreitas.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

class ForwardedIdentityIT extends IntegrationTestSupport {

    private static final String UUID_PATTERN = "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}";

    @BeforeEach
    void servicesAreUp() {
        servicesAnswerAnything();
    }

    @Test
    void tellsTheServiceWhoIsCalling() {
        TestUser ana = TestUser.ana();

        client.get().uri("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, bearer(ana))
                .exchange()
                .expectStatus().isOk();

        LoggedRequest received = orderReceived();
        assertThat(valuesOf(received, "X-User-Id")).containsExactly(ana.id());
        assertThat(valuesOf(received, "X-User-Name")).containsExactly("Ana Souza");
        assertThat(valuesOf(received, "X-User-Email")).containsExactly("ana@exemplo.com");
        assertThat(valuesOf(received, "X-User-Roles")).containsExactly("customer");
    }

    @Test
    void sendsEveryRealmRoleOfTheUser() {
        client.get().uri("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, bearer(TestUser.admin()))
                .exchange()
                .expectStatus().isOk();

        assertThat(valuesOf(orderReceived(), "X-User-Roles")).containsExactly("admin,customer");
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

        // Every value of each header, not just a match: a forged value arriving next to the real one is a failure.
        LoggedRequest received = orderReceived();
        assertThat(valuesOf(received, "X-User-Id")).containsExactly(ana.id());
        assertThat(valuesOf(received, "X-User-Name")).containsExactly("Ana Souza");
        assertThat(valuesOf(received, "X-User-Email")).containsExactly("ana@exemplo.com");
        assertThat(valuesOf(received, "X-User-Roles")).containsExactly("customer");
    }

    @Test
    void dropsUserHeadersOnRoutesWithoutLogin() {
        client.get().uri("/api/products")
                .header("X-User-Id", "someone-else")
                .header("X-User-Roles", "admin")
                .exchange()
                .expectStatus().isOk();

        inventoryService.verify(getRequestedFor(urlEqualTo("/products"))
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

        inventoryService.verify(getRequestedFor(urlEqualTo("/products"))
                .withHeader(CORRELATION_ID, equalTo(returned)));
    }

    @Test
    void keepsTheCorrelationIdOfTheCaller() {
        client.get().uri("/api/products")
                .header(CORRELATION_ID, "checkout-42")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(CORRELATION_ID, "checkout-42");

        inventoryService.verify(getRequestedFor(urlEqualTo("/products"))
                .withHeader(CORRELATION_ID, equalTo("checkout-42")));
    }

    @Test
    void returnsOneCorrelationIdEvenWhenTheServiceEchoesIt() {
        inventoryService.stubFor(any(urlEqualTo("/products"))
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

        inventoryService.verify(getRequestedFor(urlEqualTo("/products"))
                .withHeader(CORRELATION_ID, matching(UUID_PATTERN)));
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

    private static LoggedRequest orderReceived() {
        List<LoggedRequest> requests = orderService.findAll(getRequestedFor(urlEqualTo("/orders")));
        assertThat(requests).hasSize(1);
        return requests.getFirst();
    }

    private static List<String> valuesOf(LoggedRequest request, String header) {
        HttpHeader sent = request.header(header);
        return sent.isPresent() ? sent.values() : List.of();
    }
}
