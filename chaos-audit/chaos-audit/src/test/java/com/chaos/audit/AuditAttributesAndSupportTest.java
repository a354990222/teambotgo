package com.chaos.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.chaos.core.context.RequestContext;
import com.chaos.core.context.RequestContextSnapshot;
import com.chaos.trace.log.MdcKeys;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * {@link AuditAttributes}、{@link AuditSupport} 与 {@link NoopAuditEventPublisher} 测试。
 */
class AuditAttributesAndSupportTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
        RequestContext.clear();
    }

    @Test
    void shouldBuildSanitizedAttributes() {
        Map<String, String> attrs = AuditAttributes.create()
                .putIfNotBlank(null, "v")
                .putIfNotBlank(" ", "v")
                .putIfNotBlank("k1", null)
                .putIfNotBlank("k1", "  ")
                .putIfNotBlank(" device ", " web ")
                .put(null, "v")
                .put(" ", "v")
                .put("k2", (String) null)
                .put(" empty ", "")
                .put(" rev ", true)
                .build();

        assertThat(attrs)
                .containsEntry("device", "web")
                .containsEntry("empty", "")
                .containsEntry("rev", "true")
                .hasSize(3);
    }

    @Test
    void shouldPopulateAuditSupportFromContextAndMdc() {
        RequestContext.set(new RequestContextSnapshot("req-trace", "sp-1", "t-1", "u-1", "app"));
        MDC.put(MdcKeys.TRACE_ID, "w3c-trace");
        MDC.put("ip", "10.0.0.1");
        MDC.put("uri", "/api/orders");

        AuditEvent event = AuditSupport.event(AuditAction.SECURITY_PERMISSION_DENIED, AuditOutcome.DENIED).build();
        assertThat(event.principalId()).isEqualTo("u-1");
        assertThat(event.tenantId()).isEqualTo("t-1");
        assertThat(event.traceId()).isEqualTo("w3c-trace");
        assertThat(event.ip()).isEqualTo("10.0.0.1");
        assertThat(event.uri()).isEqualTo("/api/orders");

        MDC.remove(MdcKeys.TRACE_ID);
        AuditEvent fallbackEvent = AuditSupport.event(AuditAction.SECURITY_PERMISSION_DENIED, AuditOutcome.DENIED).build();
        assertThat(fallbackEvent.traceId()).isEqualTo("req-trace");

        assertThat(AuditSupport.attributes(null, "v")).isEmpty();
        assertThat(AuditSupport.attributes(" ", "v")).isEmpty();
        assertThat(AuditSupport.attributes("k", null)).isEmpty();
        assertThat(AuditSupport.attributes("k", "v")).containsEntry("k", "v");

        assertThatCode(() -> new NoopAuditEventPublisher().publish(fallbackEvent)).doesNotThrowAnyException();
    }
}
