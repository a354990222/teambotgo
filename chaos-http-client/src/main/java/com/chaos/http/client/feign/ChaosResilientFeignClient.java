package com.chaos.http.client.feign;

import com.chaos.core.resilience.ResilienceExecutor;
import com.chaos.core.resilience.ResilienceRegistry;
import feign.Client;
import feign.Request;
import feign.Response;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;

/**
 * 为 OpenFeign 提供断路器、并发舱壁与调用超时预算保护的客户端装饰器。
 *
 * <p>以目标 Feign 服务名作为隔离键，仅将传输异常（{@link IOException}）、调用超时与 {@code 5xx}
 * 响应计入断路器失败率；{@code 4xx} 等客户端/业务拒绝不触发熔断。</p>
 */
public class ChaosResilientFeignClient implements Client {

    private final Client delegate;
    private final ResilienceExecutor executor;

    /**
     * 包装底层 Feign 客户端。
     */
    public ChaosResilientFeignClient(Client delegate, ResilienceRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.executor = new ResilienceExecutor(Objects.requireNonNull(registry, "registry must not be null"));
    }

    @Override
    public Response execute(Request request, Request.Options options) throws IOException {
        String resource = resolveResourceName(request);
        try {
            return executor.execute(resource, () -> {
                Response response = delegate.execute(request, options);
                if (response != null && response.status() >= 500) {
                    throw new ServerStatusFailureSignal(response);
                }
                return response;
            }, ex -> ex instanceof ServerStatusFailureSignal || ex instanceof IOException);
        } catch (ServerStatusFailureSignal signal) {
            return signal.response;
        } catch (IOException | RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("Resilient Feign invocation failed", ex);
        }
    }

    private static String resolveResourceName(Request request) {
        if (request != null
                && request.requestTemplate() != null
                && request.requestTemplate().feignTarget() != null
                && request.requestTemplate().feignTarget().name() != null
                && !request.requestTemplate().feignTarget().name().isBlank()) {
            return request.requestTemplate().feignTarget().name();
        }
        if (request != null && request.url() != null) {
            try {
                URI uri = URI.create(request.url());
                if (uri.getHost() != null && !uri.getHost().isBlank()) {
                    return uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
                }
            } catch (IllegalArgumentException ignored) {
                // fallback below
            }
        }
        return "feign-default";
    }

    private static final class ServerStatusFailureSignal extends RuntimeException {
        private final Response response;

        private ServerStatusFailureSignal(Response response) {
            super(null, null, false, false);
            this.response = response;
        }
    }
}
