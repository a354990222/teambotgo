package com.chaos.http.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * {@link ServiceHttpClients} 单元测试。
 */
class ServiceHttpClientsTest {

    @Test
    void shouldRejectNonPositiveTimeoutsAndCacheByTimeoutPair() {
        ServiceHttpClients clients = new ServiceHttpClients(new RestTemplateBuilder());

        assertThatThrownBy(() -> clients.forTimeouts(0, 1000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> clients.forTimeouts(1000, 0)).isInstanceOf(IllegalArgumentException.class);

        RestTemplate first = clients.forTimeouts(500, 1500);
        RestTemplate second = clients.forTimeouts(500, 1500);
        RestTemplate different = clients.forTimeouts(500, 2000);

        assertThat(first).isSameAs(second);
        assertThat(first).isNotSameAs(different);
    }

    @Test
    void shouldDisableAutomaticRedirects() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            byte[] body = "redirected".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ServiceHttpClients clients = new ServiceHttpClients(new RestTemplateBuilder());
            RestTemplate restTemplate = clients.forTimeouts(1000, 1000);
            ResponseEntity<String> response = restTemplate.getForEntity(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect",
                    String.class);
            assertThat(response.getStatusCode().value()).isEqualTo(302);
        } finally {
            server.stop(0);
        }
    }
}
