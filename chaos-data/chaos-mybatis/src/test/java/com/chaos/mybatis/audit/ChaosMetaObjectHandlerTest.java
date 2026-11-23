package com.chaos.mybatis.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.chaos.mybatis.entity.BaseEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 审计字段填充测试。
 */
class ChaosMetaObjectHandlerTest {

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), SampleEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), BooleanLogicEntity.class);
    }

    /**
     * 不依赖 security 时也应填充时间和逻辑删除标记，操作人来自提供者。
     */
    @Test
    void shouldFillTimestampsAndDeletedWithoutSecurity() {
        SampleEntity entity = new SampleEntity();
        MetaObject metaObject = SystemMetaObject.forObject(entity);
        Clock fixedClock = Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneOffset.UTC);

        ChaosMetaObjectHandler handler = new ChaosMetaObjectHandler(() -> "10001", fixedClock);
        handler.insertFill(metaObject);

        assertThat(entity.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 6, 1, 10, 0));
        assertThat(entity.getUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 6, 1, 10, 0));
        assertThat(entity.getDeleted()).isZero();
        assertThat(entity.getCreatedBy()).isEqualTo("10001");

        entity.setUpdatedAt(null);
        entity.setUpdatedBy(null);
        handler.updateFill(SystemMetaObject.forObject(entity));
        assertThat(entity.getUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 6, 1, 10, 0));
        assertThat(entity.getUpdatedBy()).isEqualTo("10001");
    }

    /**
     * 取不到操作人时字段保持为空，而不是写入空字符串；已有逻辑删除值不覆盖；支持布尔逻辑删除。
     */
    @Test
    void shouldLeaveAuditorEmptyWhenUnknownAndSupportBooleanLogicDelete() {
        SampleEntity entity = new SampleEntity();
        entity.setDeleted(1);

        new ChaosMetaObjectHandler(() -> "").insertFill(SystemMetaObject.forObject(entity));

        assertThat(entity.getCreatedBy()).isNull();
        assertThat(entity.getDeleted()).isEqualTo(1);

        BooleanLogicEntity boolEntity = new BooleanLogicEntity();
        new ChaosMetaObjectHandler().insertFill(SystemMetaObject.forObject(boolEntity));
        assertThat(boolEntity.getDeletedFlag()).isTrue();
    }

    /**
     * 布尔逻辑删除测试实体。
     */
    public static class BooleanLogicEntity {
        private Long id;

        @TableLogic(value = "true", delval = "false")
        private Boolean deletedFlag;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Boolean getDeletedFlag() {
            return deletedFlag;
        }

        public void setDeletedFlag(Boolean deletedFlag) {
            this.deletedFlag = deletedFlag;
        }
    }

    /**
     * 测试实体。
     */
    public static class SampleEntity extends BaseEntity {

        private Long id;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }
    }
}
