package com.chaos.mybatis.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.DbType;
import com.chaos.core.context.RequestContext;
import com.chaos.mybatis.audit.RequestContextAuditorProvider;
import com.chaos.tenant.TenantContext;
import com.chaos.tenant.TenantDescriptor;
import com.chaos.tenant.TenantIsolationMode;
import com.chaos.tenant.TenantPlan;
import com.chaos.tenant.TenantStatus;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ChaosMybatisProperties} 与默认上下文提供者测试。
 */
class ChaosMybatisPropertiesAndProvidersTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        RequestContext.clear();
    }

    @Test
    void shouldNormalizeNullPropertiesToDefaults() {
        ChaosMybatisProperties properties = new ChaosMybatisProperties();
        properties.setDbType(null);
        properties.setTenant(null);
        properties.setDataScope(null);
        properties.setIdGenerator(null);
        properties.setPagination(null);
        properties.setOptimisticLock(null);

        assertThat(properties.getDbType()).isEqualTo(DbType.MYSQL);
        assertThat(properties.getTenant()).isNotNull();
        assertThat(properties.getDataScope()).isNotNull();
        assertThat(properties.getIdGenerator()).isNotNull();
        assertThat(properties.getPagination()).isNotNull();
        assertThat(properties.getOptimisticLock()).isNotNull();

        properties.getTenant().setIgnoreTablePrefixes(null);
        properties.getTenant().setIgnoreTables(null);
        properties.getTenant().setMissingTenantBehavior(null);
        assertThat(properties.getTenant().getIgnoreTablePrefixes()).isEmpty();
        assertThat(properties.getTenant().getIgnoreTables()).isEmpty();
        assertThat(properties.getTenant().getMissingTenantBehavior())
                .isEqualTo(ChaosMybatisProperties.MissingTenantBehavior.DENY);

        properties.getDataScope().setIgnoreTablePrefixes(null);
        properties.getDataScope().setIgnoreTables(null);
        properties.getDataScope().setEmptyConditionBehavior(null);
        assertThat(properties.getDataScope().getIgnoreTablePrefixes()).isEmpty();
        assertThat(properties.getDataScope().getIgnoreTables()).isEmpty();
        assertThat(properties.getDataScope().getEmptyConditionBehavior())
                .isEqualTo(ChaosMybatisProperties.EmptyConditionBehavior.DENY);

        properties.getTenant().setIgnoreTables(List.of("audit_log"));
        properties.getDataScope().setIgnoreTables(List.of("audit_log"));
        properties.getPagination().setMaxLimit(200);
        properties.getPagination().setOverflow(true);
        properties.getOptimisticLock().setEnabled(false);
        properties.getIdGenerator().setWorkerId(1L);
        properties.getIdGenerator().setDatacenterId(2L);

        assertThat(properties.getTenant().getIgnoreTables()).containsExactly("audit_log");
        assertThat(properties.getPagination().getMaxLimit()).isEqualTo(200);
        assertThat(properties.getPagination().isOverflow()).isTrue();
        assertThat(properties.getOptimisticLock().isEnabled()).isFalse();
        assertThat(properties.getIdGenerator().getWorkerId()).isEqualTo(1L);
        assertThat(properties.getIdGenerator().getDatacenterId()).isEqualTo(2L);
    }

    @Test
    void shouldResolveTenantAndAuditorFromContexts() {
        RequestContext.setTenantId("req-tenant");
        RequestContext.setUserId("req-user");
        RequestContextTenantIdProvider tenantProvider = new RequestContextTenantIdProvider();
        RequestContextAuditorProvider auditorProvider = new RequestContextAuditorProvider();

        assertThat(tenantProvider.currentTenantId()).isEqualTo("req-tenant");
        assertThat(auditorProvider.currentAuditor()).isEqualTo("req-user");

        TenantContext.set(new TenantDescriptor(
                "explicit-tenant", TenantStatus.ACTIVE, TenantPlan.empty(), TenantIsolationMode.SHARED_SCHEMA));
        assertThat(tenantProvider.currentTenantId()).isEqualTo("explicit-tenant");
    }
}
