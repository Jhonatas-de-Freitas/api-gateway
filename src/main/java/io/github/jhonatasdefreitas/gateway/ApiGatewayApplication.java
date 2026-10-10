package io.github.jhonatasdefreitas.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

import io.micrometer.core.instrument.config.MeterFilter;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }

    // The metrics are served on the same port as the API, so they must not say where the services and
    // Redis are.
    @Bean
    MeterFilter metricsWithoutInternalAddresses() {
        return MeterFilter.ignoreTags("routeUri", "spring.cloud.gateway.route.uri", "net.sock.peer.addr",
                "net.sock.peer.port");
    }
}
