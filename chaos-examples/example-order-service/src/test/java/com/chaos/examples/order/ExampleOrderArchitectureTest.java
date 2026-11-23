package com.chaos.examples.order;

import com.chaos.test.architecture.DddArchitectureAssertions;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 订单服务 DDD 分层与命名约束验证。
 */
class ExampleOrderArchitectureTest {

    @Test
    void shouldFollowDddLayeredArchitecture() {
        DddArchitectureAssertions.assertLayeredArchitecture(Path.of("src/main/java"), "com.chaos.examples.order");
    }
}
