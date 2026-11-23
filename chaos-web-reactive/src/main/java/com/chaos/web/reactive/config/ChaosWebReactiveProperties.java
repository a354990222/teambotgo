package com.chaos.web.reactive.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * WebFlux 响应式业务服务配置属性（{@code chaos.web.reactive.*}）。
 */
@Validated
@ConfigurationProperties(prefix = "chaos.web.reactive")
public class ChaosWebReactiveProperties {

    /**
     * 是否启用响应式业务 Web 治理装配（包含统一响应、全局异常、Trace、限流、幂等与基础安全响应头）。
     */
    private boolean enabled = true;

    /**
     * 是否自动将控制器非 {@code ApiResult} 返回值包装为统一 {@code ApiResult}。
     */
    private boolean responseWrapEnabled = true;

    /**
     * 跳过统一响应自动包装的请求路径模式（Ant 风格）。
     */
    @NotNull(message = "chaos.web.reactive.response-wrap-exclude-paths must not be null")
    private List<String> responseWrapExcludePaths = new ArrayList<>(
            List.of("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**"));

    /**
     * 是否启用响应式 Trace / 身份上下文透传过滤器。
     */
    private boolean traceEnabled = true;

    /**
     * 是否启用基础安全响应头与查询参数危险片段拦截。
     */
    private boolean xssEnabled = true;

    /**
     * 响应式限流配置。
     */
    @Valid
    @NotNull(message = "chaos.web.reactive.rate-limit must not be null")
    private RateLimit rateLimit = new RateLimit();

    /**
     * 响应式幂等防重配置。
     */
    @Valid
    @NotNull(message = "chaos.web.reactive.idempotent must not be null")
    private Idempotent idempotent = new Idempotent();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isResponseWrapEnabled() {
        return responseWrapEnabled;
    }

    public void setResponseWrapEnabled(boolean responseWrapEnabled) {
        this.responseWrapEnabled = responseWrapEnabled;
    }

    public List<String> getResponseWrapExcludePaths() {
        return responseWrapExcludePaths;
    }

    public void setResponseWrapExcludePaths(List<String> responseWrapExcludePaths) {
        this.responseWrapExcludePaths = responseWrapExcludePaths == null
                ? new ArrayList<>()
                : responseWrapExcludePaths;
    }

    public boolean isTraceEnabled() {
        return traceEnabled;
    }

    public void setTraceEnabled(boolean traceEnabled) {
        this.traceEnabled = traceEnabled;
    }

    public boolean isXssEnabled() {
        return xssEnabled;
    }

    public void setXssEnabled(boolean xssEnabled) {
        this.xssEnabled = xssEnabled;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit == null ? new RateLimit() : rateLimit;
    }

    public Idempotent getIdempotent() {
        return idempotent;
    }

    public void setIdempotent(Idempotent idempotent) {
        this.idempotent = idempotent == null ? new Idempotent() : idempotent;
    }

    /**
     * 响应式全局限流配置。
     */
    public static class RateLimit {

        /**
         * 是否启用响应式全局限流过滤器。
         */
        private boolean enabled = true;

        /**
         * 默认每秒允许请求数。
         */
        @Min(value = 1, message = "chaos.web.reactive.rate-limit.default-permits-per-second must be >= 1")
        private int defaultPermitsPerSecond = 100;

        /**
         * 内存限流器保留的最大 key 数量。
         */
        @Min(value = 1, message = "chaos.web.reactive.rate-limit.max-local-keys must be >= 1")
        private int maxLocalKeys = 10_000;

        /**
         * Redis 等远端限流器故障时是否放行。
         */
        private boolean failOpen = true;

        /**
         * 跳过限流的 Ant 路径列表。
         */
        @NotNull(message = "chaos.web.reactive.rate-limit.skip-paths must not be null")
        private List<String> skipPaths = new ArrayList<>(List.of("/actuator/health/**"));

        /**
         * 响应式限流 Redis key 前缀。
         */
        @NotBlank(message = "chaos.web.reactive.rate-limit.key-prefix must not be blank")
        private String keyPrefix = "chaos:web-reactive:rate-limit:";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getDefaultPermitsPerSecond() {
            return defaultPermitsPerSecond;
        }

        public void setDefaultPermitsPerSecond(int defaultPermitsPerSecond) {
            this.defaultPermitsPerSecond = defaultPermitsPerSecond;
        }

        public int getMaxLocalKeys() {
            return maxLocalKeys;
        }

        public void setMaxLocalKeys(int maxLocalKeys) {
            this.maxLocalKeys = maxLocalKeys;
        }

        public boolean isFailOpen() {
            return failOpen;
        }

        public void setFailOpen(boolean failOpen) {
            this.failOpen = failOpen;
        }

        public List<String> getSkipPaths() {
            return skipPaths;
        }

        public void setSkipPaths(List<String> skipPaths) {
            this.skipPaths = skipPaths == null ? new ArrayList<>() : skipPaths;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = (keyPrefix == null || keyPrefix.isBlank())
                    ? "chaos:web-reactive:rate-limit:"
                    : keyPrefix;
        }
    }

    /**
     * 响应式请求幂等校验配置。
     */
    public static class Idempotent {

        /**
         * 是否启用基于请求头的响应式幂等拦截（仅当请求携带幂等头时校验）。
         */
        private boolean enabled = true;

        /**
         * 携带客户端幂等请求键的 HTTP 请求头名。
         */
        @NotBlank(message = "chaos.web.reactive.idempotent.header-name must not be blank")
        private String headerName = "Idempotency-Key";

        /**
         * 幂等键占用锁定的默认存活时间。
         */
        @NotNull(message = "chaos.web.reactive.idempotent.ttl must not be null")
        private Duration ttl = Duration.ofMinutes(10);

        /**
         * 内存幂等仓储保留的最大 key 数量。
         */
        @Min(value = 1, message = "chaos.web.reactive.idempotent.max-local-keys must be >= 1")
        private int maxLocalKeys = 10_000;

        /**
         * 需要检查幂等请求头的 HTTP 方法列表。
         */
        @NotNull(message = "chaos.web.reactive.idempotent.methods must not be null")
        private List<String> methods = new ArrayList<>(List.of("POST", "PUT", "PATCH", "DELETE"));

        /**
         * 响应式幂等 Redis key 前缀。
         */
        @NotBlank(message = "chaos.web.reactive.idempotent.key-prefix must not be blank")
        private String keyPrefix = "chaos:web-reactive:idempotent:";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getHeaderName() {
            return headerName;
        }

        public void setHeaderName(String headerName) {
            this.headerName = (headerName == null || headerName.isBlank()) ? "Idempotency-Key" : headerName;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl == null ? Duration.ofMinutes(10) : ttl;
        }

        public int getMaxLocalKeys() {
            return maxLocalKeys;
        }

        public void setMaxLocalKeys(int maxLocalKeys) {
            this.maxLocalKeys = maxLocalKeys;
        }

        public List<String> getMethods() {
            return methods;
        }

        public void setMethods(List<String> methods) {
            this.methods = methods == null ? new ArrayList<>() : methods;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = (keyPrefix == null || keyPrefix.isBlank())
                    ? "chaos:web-reactive:idempotent:"
                    : keyPrefix;
        }
    }
}
