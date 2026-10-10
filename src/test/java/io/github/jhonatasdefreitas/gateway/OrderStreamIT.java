package io.github.jhonatasdefreitas.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;

import reactor.core.publisher.Timed;

class OrderStreamIT extends IntegrationTestSupport {

    private static final int EVENTS = 5;

    @Test
    void relaysEachEventAsItComes() {
        orderService.stubFor(get("/orders/stream").willReturn(eventsSpreadOver(3000)));

        List<Timed<ServerSentEvent<String>>> received = openStream();

        assertThat(received).extracting(event -> event.get().event()).containsOnly("order-updated");
        assertThat(received).extracting(event -> event.get().data()).containsExactly(
                "{\"id\":\"order-1\",\"status\":\"CONFIRMED\"}",
                "{\"id\":\"order-2\",\"status\":\"CONFIRMED\"}",
                "{\"id\":\"order-3\",\"status\":\"CONFIRMED\"}",
                "{\"id\":\"order-4\",\"status\":\"CONFIRMED\"}",
                "{\"id\":\"order-5\",\"status\":\"CONFIRMED\"}");

        Duration first = received.getFirst().elapsedSinceSubscription();
        Duration last = received.getLast().elapsedSinceSubscription();
        assertThat(last.minus(first))
                .as("time between the first and the last event")
                .isGreaterThan(Duration.ofMillis(1500));
    }

    @Test
    void waitsForAStreamThatTakesLongToSayAnything() {
        // The other routes of this context give up on a service after 1 s.
        orderService.stubFor(get("/orders/stream").willReturn(eventsSpreadOver(500).withFixedDelay(2500)));

        assertThat(openStream()).hasSize(EVENTS);
    }

    @Test
    void streamNeedsALogin() {
        expectProblem(client.get().uri("/api/orders/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange(), HttpStatus.UNAUTHORIZED, "/api/orders/stream");
    }

    private List<Timed<ServerSentEvent<String>>> openStream() {
        return client.get().uri("/api/orders/stream")
                .header(HttpHeaders.AUTHORIZATION, bearer(TestUser.ana()))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .getResponseBody()
                .timed()
                .collectList()
                .block(Duration.ofSeconds(15));
    }

    // Events of the same size, so WireMock sends exactly one per chunk.
    private static ResponseDefinitionBuilder eventsSpreadOver(int milliseconds) {
        String events = IntStream.rangeClosed(1, EVENTS)
                .mapToObj(number -> "event: order-updated\ndata: {\"id\":\"order-" + number
                        + "\",\"status\":\"CONFIRMED\"}\n\n")
                .collect(Collectors.joining());
        return aResponse()
                .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                .withBody(events)
                .withChunkedDribbleDelay(EVENTS, milliseconds);
    }
}
