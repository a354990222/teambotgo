package com.chaos.http.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * {@link ClientCredentialsTokenProvider} 单元测试。
 */
class ClientCredentialsTokenProviderTest {

    @Test
    void shouldRejectBlankClientCredentials() {
        ServiceHttpClients clients = new ServiceHttpClients(new RestTemplateBuilder());

        assertThatThrownBy(() -> new ClientCredentialsTokenProvider(
                "https://auth.internal", "", "secret", "read", clients))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientCredentialsTokenProvider(
                "https://auth.internal", "svc", " ", "read", clients))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldCacheValidTokenAndRefreshAfterInvalidate() {
        ServiceHttpClients clients = new ServiceHttpClients(new RestTemplateBuilder());
        RestTemplate restTemplate = clients.forTimeouts(2500, 5000);
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

        server.expect(requestTo("https://auth.internal/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, org.hamcrest.Matchers.startsWith("Basic ")))
                .andRespond(withSuccess("{\"access_token\":\"tok-1\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://auth.internal/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"tok-2\",\"expires_in\":300}", MediaType.APPLICATION_JSON));

        ClientCredentialsTokenProvider provider = new ClientCredentialsTokenProvider(
                "https://auth.internal/", "order-svc", "s3cr3t", "internal.read", clients);

        assertThat(provider.token()).isEqualTo("tok-1");
        assertThat(provider.token()).isEqualTo("tok-1");

        provider.invalidate();
        assertThat(provider.token()).isEqualTo("tok-2");
        server.verify();
    }

    @Test
    void shouldFailWhenTokenResponseInvalid() {
        ServiceHttpClients clients = new ServiceHttpClients(new RestTemplateBuilder());
        RestTemplate restTemplate = clients.forTimeouts(2500, 5000);
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("https://auth.internal/oauth2/token"))
                .andRespond(withSuccess("{\"access_token\":\"\",\"expires_in\":0}", MediaType.APPLICATION_JSON));

        ClientCredentialsTokenProvider provider = new ClientCredentialsTokenProvider(
                "https://auth.internal", "order-svc", "s3cr3t", "internal.read", clients);

        assertThatThrownBy(provider::token)
                .isInstanceOf(IllegalStateException.class);
        server.verify();
    }
}
