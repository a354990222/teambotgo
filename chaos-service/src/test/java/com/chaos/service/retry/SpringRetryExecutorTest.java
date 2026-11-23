package com.chaos.service.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chaos.core.exception.BizException;
import com.chaos.core.exception.CommonErrorCode;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.retry.support.RetryTemplate;

/**
 * {@link SpringRetryExecutor} 单元测试。
 */
class SpringRetryExecutorTest {

    @Test
    void shouldUseDefaultTemplateAndScopedRetryExceptions() {
        RetryTemplate defaultTemplate = RetryTemplate.builder()
                .maxAttempts(2)
                .noBackoff()
                .notRetryOn(List.of(BizException.class))
                .build();
        SpringRetryExecutor executor = new SpringRetryExecutor(defaultTemplate, 3, 0);

        AtomicInteger runnableAttempts = new AtomicInteger();
        executor.execute((Runnable) () -> {
            if (runnableAttempts.incrementAndGet() < 2) {
                throw new IllegalStateException("transient");
            }
        });
        assertThat(runnableAttempts).hasValue(2);

        AtomicInteger scopedSupplierAttempts = new AtomicInteger();
        String result = executor.execute(() -> {
            if (scopedSupplierAttempts.incrementAndGet() < 3) {
                throw new IllegalArgumentException("retryable");
            }
            return "done";
        }, IllegalArgumentException.class);
        assertThat(result).isEqualTo("done");
        assertThat(scopedSupplierAttempts).hasValue(3);

        AtomicInteger nonRetryableAttempts = new AtomicInteger();
        assertThatThrownBy(() -> executor.execute((Runnable) () -> {
            nonRetryableAttempts.incrementAndGet();
            throw new BizException(CommonErrorCode.BAD_REQUEST);
        }, IllegalArgumentException.class)).isInstanceOf(BizException.class);
        assertThat(nonRetryableAttempts).hasValue(1);
    }

    @Test
    void shouldSupportEmptyRetryOnAndRunnableOverload() {
        RetryTemplate template = RetryTemplate.builder().maxAttempts(2).noBackoff().build();
        SpringRetryExecutor executor = new SpringRetryExecutor(template);

        AtomicInteger supplierAttempts = new AtomicInteger();
        String ok = executor.execute(() -> {
            if (supplierAttempts.incrementAndGet() < 2) {
                throw new IllegalStateException("again");
            }
            return "ok";
        });
        assertThat(ok).isEqualTo("ok");

        AtomicInteger runnableScopedAttempts = new AtomicInteger();
        executor.execute(() -> {
            if (runnableScopedAttempts.incrementAndGet() < 2) {
                throw new IllegalStateException("retry");
            }
        }, IllegalStateException.class);
        assertThat(runnableScopedAttempts).hasValue(2);
    }
}
