package ${package};

import com.chaos.test.architecture.DddArchitectureAssertions;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * DDD 分层与命名约束验证。
 */
class ArchitectureTest {

    @Test
    void shouldFollowDddLayeredArchitecture() {
        DddArchitectureAssertions.assertLayeredArchitecture(Path.of("src/main/java"), "${package}");
    }
}
