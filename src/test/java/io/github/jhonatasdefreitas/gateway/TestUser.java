package io.github.jhonatasdefreitas.gateway;

import java.util.List;
import java.util.UUID;

/**
 * The demo users of the contract. Each call gets a new id, so a test never shares a rate limit bucket
 * with another one.
 */
record TestUser(String id, String username, String name, String email, List<String> roles) {

    static TestUser ana() {
        return new TestUser(UUID.randomUUID().toString(), "ana", "Ana Souza", "ana@exemplo.com",
                List.of("customer"));
    }

    static TestUser admin() {
        return new TestUser(UUID.randomUUID().toString(), "admin", "Admin Loja", "admin@exemplo.com",
                List.of("admin", "customer"));
    }
}
