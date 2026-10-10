package io.github.jhonatasdefreitas.gateway.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class GatewayErrorHandlerTest {

    @Test
    void serviceThatCannotBeReachedIsUnavailable() {
        assertThat(GatewayErrorHandler.statusFor(new ConnectException("Connection refused")))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(GatewayErrorHandler.statusFor(new IllegalStateException(new UnknownHostException("order-service"))))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(GatewayErrorHandler.statusFor(new TimeoutException()))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void keepsTheStatusTheFrameworkAlreadyChose() {
        assertThat(GatewayErrorHandler.statusFor(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anythingElseIsABugOfTheGateway() {
        assertThat(GatewayErrorHandler.statusFor(new IllegalStateException("boom")))
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
