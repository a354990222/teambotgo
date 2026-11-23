package com.chaos.http.client;

import com.chaos.core.resilience.ResilienceExecutor;
import com.chaos.core.resilience.ResilienceRegistry;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * 为 {@code RestTemplate} / {@link ServiceHttpClients} 提供按目标主机隔离的断路器、舱壁与超时预算保护。
 */
public class ResilientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

    private final ResilienceExecutor executor;

    /**
     * 创建受保护 HTTP 请求拦截器。
     */
    public ResilientHttpRequestInterceptor(ResilienceRegistry registry) {
        this.executor = new ResilienceExecutor(Objects.requireNonNull(registry, "registry must not be null"));
    }

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request,
            byte[] body,
            ClientHttpRequestExecution execution) throws IOException {
        String resource = resolveTarget(request.getURI());
        try {
            return executor.execute(resource, () -> {
                ClientHttpResponse response = execution.execute(request, body);
                if (response.getStatusCode().is5xxServerError()) {
                    throw new ServerStatusSignal(response);
                }
                return response;
            }, ex -> ex instanceof ServerStatusSignal || ex instanceof IOException);
        } catch (ServerStatusSignal signal) {
            return signal.response;
        } catch (IOException | RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("Resilient HTTP invocation failed", ex);
        }
    }

    private static String resolveTarget(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getHost().isBlank()) {
            return "http-default";
        }
        return uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
    }

    private static final class ServerStatusSignal extends RuntimeException {
        private final ClientHttpResponse response;

        private ServerStatusSignal(ClientHttpResponse response) {
            super(null, null, false, false);
            this.response = response;
        }
    }
}
