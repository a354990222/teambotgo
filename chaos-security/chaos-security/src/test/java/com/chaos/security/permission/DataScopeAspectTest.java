package com.chaos.security.permission;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaos.security.annotation.DataScope;
import com.chaos.security.api.auth.LoginUser;
import com.chaos.security.api.datascope.DataScopeContext;
import com.chaos.security.api.datascope.DataScopeRequest;
import com.chaos.security.support.AspectTestSupport;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@link DataScopeAspect} 单元测试。
 */
class DataScopeAspectTest {

    private final DataScopeAspect aspect = new DataScopeAspect();

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        DataScopeContext.clear();
    }

    @Test
    void shouldPushMethodAndClassDataScopeAndPopAfterExecution() throws Throwable {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser("1001", "alice", "tenant-a", Set.of("admin"), Set.of()), null, List.of()));

        Method methodAnnotated = SampleService.class.getMethod("methodScoped");
        AtomicReference<DataScopeRequest> observed = new AtomicReference<>();
        Object result = aspect.applyDataScope(capturingJoinPoint(methodAnnotated, observed));

        assertThat(result).isEqualTo(AspectTestSupport.PROCEED_RESULT);
        assertThat(observed.get()).isNotNull();
        assertThat(observed.get().scope()).isEqualTo("order-method");
        assertThat(observed.get().subject().userId()).isEqualTo("1001");
        assertThat(DataScopeContext.currentRequest()).isEmpty();

        Method classAnnotated = SampleService.class.getMethod("classScoped");
        aspect.applyDataScope(capturingJoinPoint(classAnnotated, observed));
        assertThat(observed.get().scope()).isEqualTo("order-class");
        assertThat(DataScopeContext.currentRequest()).isEmpty();

        Method unannotated = PlainService.class.getMethod("plain");
        assertThat(aspect.applyDataScope(AspectTestSupport.joinPoint(unannotated)))
                .isEqualTo(AspectTestSupport.PROCEED_RESULT);
        assertThat(DataScopeContext.currentRequest()).isEmpty();
    }

    private static ProceedingJoinPoint capturingJoinPoint(Method method, AtomicReference<DataScopeRequest> observed) {
        MethodSignature signature = (MethodSignature) Proxy.newProxyInstance(
                MethodSignature.class.getClassLoader(),
                new Class<?>[]{MethodSignature.class},
                (proxy, invokedMethod, invokedArgs) -> switch (invokedMethod.getName()) {
                    case "getMethod" -> method;
                    case "getDeclaringType" -> method.getDeclaringClass();
                    case "getName" -> method.getName();
                    default -> null;
                });
        return (ProceedingJoinPoint) Proxy.newProxyInstance(
                ProceedingJoinPoint.class.getClassLoader(),
                new Class<?>[]{ProceedingJoinPoint.class},
                (proxy, invokedMethod, invokedArgs) -> switch (invokedMethod.getName()) {
                    case "getSignature" -> signature;
                    case "proceed" -> {
                        observed.set(DataScopeContext.currentRequest().orElse(null));
                        yield AspectTestSupport.PROCEED_RESULT;
                    }
                    default -> null;
                });
    }

    @DataScope("order-class")
    static class SampleService {

        @DataScope("order-method")
        public String methodScoped() {
            return "ok";
        }

        public String classScoped() {
            return "ok";
        }
    }

    static class PlainService {

        public String plain() {
            return "ok";
        }
    }
}
