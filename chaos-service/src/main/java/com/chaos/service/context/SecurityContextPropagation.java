package com.chaos.service.context;

import java.util.Objects;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 可选的 Spring Security 上下文跨线程传递辅助类。
 *
 * <p>仅在 classpath 存在 {@code spring-security-core} 时由 {@link TraceContextTaskDecorator} 调用，
 * 避免无安全依赖的服务加载失败。在执行线程恢复提交线程的安全上下文，并在结束后还原原线程上下文。</p>
 */
final class SecurityContextPropagation {

    private SecurityContextPropagation() {
    }

    static Runnable wrap(Runnable delegate) {
        Objects.requireNonNull(delegate, "delegate must not be null");
        SecurityContext captured = SecurityContextHolder.getContext();
        return () -> {
            SecurityContext previous = SecurityContextHolder.getContext();
            try {
                if (captured == null || captured.getAuthentication() == null) {
                    SecurityContextHolder.clearContext();
                } else {
                    SecurityContext copy = SecurityContextHolder.createEmptyContext();
                    copy.setAuthentication(captured.getAuthentication());
                    SecurityContextHolder.setContext(copy);
                }
                delegate.run();
            } finally {
                if (previous == null || previous.getAuthentication() == null) {
                    SecurityContextHolder.clearContext();
                } else {
                    SecurityContextHolder.setContext(previous);
                }
            }
        };
    }
}
