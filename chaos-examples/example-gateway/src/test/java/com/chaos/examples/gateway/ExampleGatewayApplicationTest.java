package com.chaos.examples.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * {@code example-gateway} 网关启动与安全边界集成测试。
 */
@SpringBootTest(
        classes = ExampleGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExampleGatewayApplicationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void shouldBootGatewayAllowHealthAndRejectUnauthenticatedApiRequests() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");

        webTestClient.get()
                .uri("/api/orders/1")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isNotEmpty();

        ExampleGatewayApplication.main(new String[] {
                "--spring.main.web-application-type=none",
                "--spring.main.banner-mode=off",
                "--spring.cloud.gateway.enabled=false",
                "--spring.cloud.gateway.server.webflux.enabled=false",
                "--chaos.gateway.enabled=false"
        });
    }
}
