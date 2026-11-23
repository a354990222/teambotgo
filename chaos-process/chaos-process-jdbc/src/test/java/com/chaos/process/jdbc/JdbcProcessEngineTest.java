package com.chaos.process.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chaos.process.ProcessDefinition;
import com.chaos.process.ProcessInstance;
import com.chaos.process.ProcessInstanceCommand;
import com.chaos.process.ProcessInstanceStatus;
import com.chaos.process.ProcessNode;
import com.chaos.process.ProcessNodeType;
import com.chaos.process.ProcessTask;
import com.chaos.process.ProcessTaskClaimCommand;
import com.chaos.process.ProcessTaskCompletionCommand;
import com.chaos.process.ProcessTransition;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcProcessEngineTest {

    private JdbcTemplate jdbcTemplate;
    private JdbcProcessEngine engine;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:process_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/chaos-process-schema-h2.sql"))
                .execute(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
        engine = new JdbcProcessEngine(jdbcTemplate, new ObjectMapper());
        engine.register(definition());
    }

    @Test
    void shouldStartClaimCompleteAndReplay() {
        ProcessInstanceCommand start = new ProcessInstanceCommand(
                "tenant-a", "review", "order", "order-1", "ORD-1", Map.of(), "start-1", "maker");
        ProcessInstance instance = engine.start(start);
        ProcessTask ready = engine.taskFor(instance.id());
        ProcessTask claimed = engine.claim(new ProcessTaskClaimCommand(
                "tenant-a", ready.id(), "reviewer", Set.of(), ready.version()));
        ProcessTask completed = engine.complete(new ProcessTaskCompletionCommand(
                "tenant-a", claimed.id(), "APPROVE", "reviewer", "ok", claimed.version(), "complete-1"));

        assertThat(completed.completionEvent()).isEqualTo("APPROVE");
        assertThat(engine.start(start).id()).isEqualTo(instance.id());
        assertThat(engine.complete(new ProcessTaskCompletionCommand(
                "tenant-a", claimed.id(), "APPROVE", "reviewer", "ok", claimed.version(), "complete-1")))
                .isEqualTo(completed);
    }

    @Test
    void shouldAllowStartingAgainAfterCompletion() {
        ProcessInstanceCommand start = new ProcessInstanceCommand(
                "tenant-a", "review", "order", "order-2", null, Map.of(), "start-2", "maker");
        ProcessInstance first = engine.start(start);
        ProcessTask ready = engine.taskFor(first.id());
        ProcessTask claimed = engine.claim(new ProcessTaskClaimCommand(
                "tenant-a", ready.id(), "reviewer", Set.of(), ready.version()));
        engine.complete(new ProcessTaskCompletionCommand(
                "tenant-a", claimed.id(), "APPROVE", "reviewer", null, claimed.version(), "complete-2"));

        ProcessInstance second = engine.start(new ProcessInstanceCommand(
                "tenant-a", "review", "order", "order-2", null, Map.of(), "start-3", "maker"));
        assertThat(second.id()).isNotEqualTo(first.id());
    }

    @Test
    void shouldRejectCompletionReplayWithDifferentPayload() {
        ProcessInstance instance = engine.start(new ProcessInstanceCommand(
                "tenant-a", "review", "order", "order-3", null, Map.of(), "start-3", "maker"));
        ProcessTask ready = engine.taskFor(instance.id());
        ProcessTask claimed = engine.claim(new ProcessTaskClaimCommand(
                "tenant-a", ready.id(), "reviewer", Set.of(), ready.version()));
        engine.complete(new ProcessTaskCompletionCommand(
                "tenant-a", claimed.id(), "APPROVE", "reviewer", "ok", claimed.version(), "complete-3"));

        assertThatThrownBy(() -> engine.complete(new ProcessTaskCompletionCommand(
                "tenant-a", claimed.id(), "APPROVE", "reviewer", "changed", claimed.version(), "complete-3")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSupportTransactionTemplateMultiStepAndSharedDefinition() {
        DriverManagerDataSource ds = (DriverManagerDataSource) jdbcTemplate.getDataSource();
        JdbcProcessEngine txEngine = new JdbcProcessEngine(
                jdbcTemplate, new ObjectMapper(), null,
                new TransactionTemplate(new DataSourceTransactionManager(ds)));
        assertThatThrownBy(() -> new JdbcProcessEngine(jdbcTemplate, new ObjectMapper(), "bad-prefix!", null))
                .isInstanceOf(IllegalArgumentException.class);

        txEngine.register(new ProcessDefinition(null, "two-step", 1,
                List.of(new ProcessNode("start", ProcessNodeType.START),
                        new ProcessNode("step1", ProcessNodeType.USER_TASK, Set.of("u1"), Set.of("ROLE_A"), Duration.ofMinutes(5)),
                        new ProcessNode("step2", ProcessNodeType.USER_TASK),
                        new ProcessNode("end", ProcessNodeType.END)),
                List.of(new ProcessTransition("start", "START", "step1"),
                        new ProcessTransition("step1", "NEXT", "step2"),
                        new ProcessTransition("step2", "DONE", "end"))));

        ProcessInstanceCommand startCmd = new ProcessInstanceCommand(
                "tenant-x", "two-step", "doc", "doc-1", "K1", Map.of(), "req-s1", "u0");
        ProcessInstance instance = txEngine.start(startCmd);

        // 同一业务仍有活跃实例时，不同 requestId 拒绝重复启动
        assertThatThrownBy(() -> txEngine.start(new ProcessInstanceCommand(
                "tenant-x", "two-step", "doc", "doc-1", "K1", Map.of(), "req-s2", "u0")))
                .isInstanceOf(IllegalStateException.class);
        // 相同 requestId 但不同指令内容拒绝重放
        assertThatThrownBy(() -> txEngine.start(new ProcessInstanceCommand(
                "tenant-x", "two-step", "doc", "doc-99", "K1", Map.of(), "req-s1", "u0")))
                .isInstanceOf(IllegalArgumentException.class);

        ProcessTask task1 = txEngine.taskFor(instance.id());
        // 非候选人不可认领
        assertThatThrownBy(() -> txEngine.claim(new ProcessTaskClaimCommand(
                "tenant-x", task1.id(), "stranger", Set.of("OTHER"), task1.version())))
                .isInstanceOf(IllegalArgumentException.class);
        // 错租户不可认领
        assertThatThrownBy(() -> txEngine.claim(new ProcessTaskClaimCommand(
                "tenant-other", task1.id(), "u1", Set.of(), task1.version())))
                .isInstanceOf(IllegalArgumentException.class);

        // 通过角色认领并模拟租约过期后被另一候选人重新认领
        ProcessTask claimedByRole = txEngine.claim(new ProcessTaskClaimCommand(
                "tenant-x", task1.id(), "u2", Set.of("ROLE_A"), task1.version()));
        String expiredJson = jdbcTemplate.queryForObject(
                "select task_json from chaos_process_task where task_id=?", String.class, claimedByRole.id())
                .replaceAll("\"claimExpiresAt\":[^,}]+", "\"claimExpiresAt\":946684800.0");
        jdbcTemplate.update(
                "update chaos_process_task set claim_expires_at = timestamp '2000-01-01 00:00:00', task_json=? where task_id=?",
                expiredJson, claimedByRole.id());
        // 原认领人租约过期后无法完成
        assertThatThrownBy(() -> txEngine.complete(new ProcessTaskCompletionCommand(
                "tenant-x", claimedByRole.id(), "NEXT", "u2", null, claimedByRole.version(), "req-exp")))
                .isInstanceOf(IllegalStateException.class);
        ProcessTask reclaimed = txEngine.claim(new ProcessTaskClaimCommand(
                "tenant-x", claimedByRole.id(), "u1", Set.of(), claimedByRole.version()));
        // 非认领人或错版本无法完成
        assertThatThrownBy(() -> txEngine.complete(new ProcessTaskCompletionCommand(
                "tenant-x", reclaimed.id(), "NEXT", "u2", null, reclaimed.version(), "req-wrong-op")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> txEngine.complete(new ProcessTaskCompletionCommand(
                "tenant-x", reclaimed.id(), "NEXT", "u1", null, reclaimed.version() + 9, "req-wrong-ver")))
                .isInstanceOf(IllegalStateException.class);

        txEngine.complete(new ProcessTaskCompletionCommand(
                "tenant-x", reclaimed.id(), "NEXT", "u1", "to step2", reclaimed.version(), "req-c1"));

        ProcessTask task2 = txEngine.taskFor(instance.id());
        ProcessTask claimed2 = txEngine.claim(new ProcessTaskClaimCommand(
                "tenant-x", task2.id(), "any-user", Set.of(), task2.version()));
        txEngine.complete(new ProcessTaskCompletionCommand(
                "tenant-x", claimed2.id(), "DONE", "any-user", "ok", claimed2.version(), "req-c2"));
        assertThatThrownBy(() -> txEngine.taskFor(instance.id()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldValidateDuplicateDefinitionDirectEndAndMissingEntities() {
        assertThatThrownBy(() -> engine.register(definition())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.start(new ProcessInstanceCommand(
                "tenant-a", "missing", "order", "1", null, Map.of(), "r1", "u")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.claim(new ProcessTaskClaimCommand("tenant-a", "missing-task", "u", Set.of(), 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.start(new ProcessInstanceCommand(
                " ", "review", "order", "1", null, Map.of(), "r1", "u")))
                .isInstanceOf(IllegalArgumentException.class);

        engine.register(new ProcessDefinition("tenant-a", "direct-end", 1,
                List.of(new ProcessNode("start", ProcessNodeType.START), new ProcessNode("end", ProcessNodeType.END)),
                List.of(new ProcessTransition("start", "START", "end"))));
        ProcessInstance instantDone = engine.start(new ProcessInstanceCommand(
                "tenant-a", "direct-end", "order", "o-end", null, Map.of(), "r-end", "u"));
        assertThat(instantDone.status()).isEqualTo(ProcessInstanceStatus.COMPLETED);
    }

    private static ProcessDefinition definition() {
        return new ProcessDefinition("tenant-a", "review", 1,
                List.of(new ProcessNode("start", ProcessNodeType.START),
                        new ProcessNode("review", ProcessNodeType.USER_TASK),
                        new ProcessNode("end", ProcessNodeType.END)),
                List.of(new ProcessTransition("start", "START", "review"),
                        new ProcessTransition("review", "APPROVE", "end")));
    }
}
