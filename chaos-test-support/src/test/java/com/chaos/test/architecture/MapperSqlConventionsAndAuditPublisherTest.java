package com.chaos.test.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chaos.audit.AuditAction;
import com.chaos.audit.AuditEvent;
import com.chaos.audit.AuditOutcome;
import com.chaos.test.audit.CapturingAuditEventPublisher;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link MapperSqlConventions} 与 {@link CapturingAuditEventPublisher} 测试。
 */
class MapperSqlConventionsAndAuditPublisherTest {

    @Test
    void shouldValidateMapperSqlAnnotationsAndXmlNamespaces(@TempDir Path tempDir) throws Exception {
        Path javaRoot = tempDir.resolve("src/main/java");
        Path mapperPkg = javaRoot.resolve("com/acme/infrastructure/persistence/mapper");
        Path xmlRoot = tempDir.resolve("src/main/resources/mapper");
        Files.createDirectories(mapperPkg);
        Files.createDirectories(xmlRoot);

        Path orderMapper = mapperPkg.resolve("OrderMapper.java");
        Files.writeString(orderMapper, "package com.acme.infrastructure.persistence.mapper; public interface OrderMapper {}");
        Path orderXml = xmlRoot.resolve("OrderMapper.xml");
        Files.writeString(orderXml, "<mapper namespace=\"com.acme.infrastructure.persistence.mapper.OrderMapper\"/>");

        assertThatCode(() -> MapperSqlConventions.assertNoSqlAnnotations(javaRoot)).doesNotThrowAnyException();
        assertThatCode(() -> MapperSqlConventions.assertEveryMapperHasXml(javaRoot, xmlRoot)).doesNotThrowAnyException();

        Files.writeString(orderMapper,
                "package com.acme.infrastructure.persistence.mapper; public interface OrderMapper { @Select(\"select 1\") int one(); }");
        assertThatThrownBy(() -> MapperSqlConventions.assertNoSqlAnnotations(javaRoot))
                .isInstanceOf(AssertionError.class);

        Files.writeString(orderXml, "<mapper/>");
        assertThatThrownBy(() -> MapperSqlConventions.assertEveryMapperHasXml(javaRoot, xmlRoot))
                .isInstanceOf(AssertionError.class);

        assertThatThrownBy(() -> MapperSqlConventions.assertNoSqlAnnotations(tempDir.resolve("missing")))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void shouldCaptureFilterAndClearAuditEvents() {
        CapturingAuditEventPublisher publisher = new CapturingAuditEventPublisher();
        assertThat(publisher.isEmpty()).isTrue();
        assertThat(publisher.firstEvent()).isEmpty();

        AuditEvent denied = AuditEvent.builder(AuditAction.SECURITY_PERMISSION_DENIED, AuditOutcome.DENIED).build();
        AuditEvent ok = AuditEvent.builder(AuditAction.AUTH_LOGIN_SUCCESS, AuditOutcome.SUCCESS).build();
        publisher.publish(denied);
        publisher.publish(ok);

        assertThat(publisher.isEmpty()).isFalse();
        assertThat(publisher.firstEvent()).contains(denied);
        assertThat(publisher.events()).containsExactly(denied, ok);
        assertThat(publisher.eventsOf(AuditAction.AUTH_LOGIN_SUCCESS)).containsExactly(ok);

        publisher.clear();
        assertThat(publisher.isEmpty()).isTrue();
    }
}
