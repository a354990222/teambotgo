package com.chaos.service.context;

import com.chaos.core.context.ContextPropagation;
import org.springframework.core.task.TaskDecorator;

/**
 * 异步任务上下文装饰器。
 *
 * <p>在任务提交线程捕获所有已注册上下文（Trace、Request、Tenant、DataScope 等），
 * 若引入了 Spring Security 则同时捕获安全上下文；在任务执行线程恢复，结束后还原执行线程原有上下文。</p>
 */
public class TraceContextTaskDecorator implements TaskDecorator {

    private static final boolean SECURITY_PRESENT = isSecurityPresent();

    @Override
    public Runnable decorate(Runnable runnable) {
        Runnable wrapped = ContextPropagation.wrap(runnable);
        return SECURITY_PRESENT ? SecurityContextPropagation.wrap(wrapped) : wrapped;
    }

    private static boolean isSecurityPresent() {
        try {
            Class.forName(
                    "org.springframework.security.core.context.SecurityContextHolder",
                    false,
                    TraceContextTaskDecorator.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
