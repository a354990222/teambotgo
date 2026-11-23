package com.chaos.process.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * {@link RedisProcessEngine} 单元测试。
 */
class RedisProcessEngineTest {

    private Map<String, String> kv;
    private Map<String, Set<String>> sets;
    private StringRedisTemplate redis;
    private RedisProcessEngine engine;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kv = new ConcurrentHashMap<>();
        sets = new ConcurrentHashMap<>();
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        SetOperations<String, String> setOps = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForSet()).thenReturn(setOps);

        when(valueOps.get(anyString())).thenAnswer(inv -> kv.get(inv.getArgument(0)));
        org.mockito.Mockito.doAnswer(inv -> {
            kv.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString());
        when(valueOps.setIfAbsent(anyString(), anyString()))
                .thenAnswer(inv -> kv.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);
        when(redis.delete(anyString())).thenAnswer(inv -> kv.remove(inv.getArgument(0)) != null);

        when(setOps.add(anyString(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            Object[] values = inv.getArguments();
            Set<String> set = sets.computeIfAbsent(key, ignored -> new ConcurrentSkipListSet<>());
            long added = 0;
            for (int i = 1; i < values.length; i++) {
                if (set.add(String.valueOf(values[i]))) {
                    added++;
                }
            }
            return added;
        });
        when(setOps.members(anyString())).thenAnswer(inv -> Set.copyOf(sets.getOrDefault(inv.getArgument(0), Set.of())));

        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(inv -> {
            List<String> keys = inv.getArgument(1);
            String key = keys.getFirst();
            Object[] args = inv.getArguments();
            String expectedSub = String.valueOf(args[2]);
            String nextJson = String.valueOf(args[3]);
            String current = kv.get(key);
            if (current == null || !current.contains(expectedSub)) {
                return 0L;
            }
            kv.put(key, nextJson);
            return 1L;
        });

        engine = new RedisProcessEngine(redis, new ObjectMapper());
        engine.register(definition());
    }

    @Test
    void shouldStartClaimMultiStepCompleteAndReplay() {
        ProcessInstanceCommand start = new ProcessInstanceCommand(
                "tenant-a", "review", "order", "o-1", "K1", Map.of("k", "v"), "req-1", "u0");
        ProcessInstance instance = engine.start(start);
        assertThat(engine.start(start).id()).isEqualTo(instance.id());

        assertThatThrownBy(() -> engine.start(new ProcessInstanceCommand(
                "tenant-a", "review", "order", "o-9", "K1", Map.of(), "req-1", "u0")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.start(new ProcessInstanceCommand(
                "tenant-a", "review", "order", "o-1", "K1", Map.of(), "req-2", "u0")))
                .isInstanceOf(IllegalStateException.class);

        ProcessTask t1 = engine.taskFor(instance.id());
        assertThatThrownBy(() -> engine.claim(new ProcessTaskClaimCommand(
                "tenant-a", t1.id(), "outsider", Set.of(), t1.version())))
                .isInstanceOf(IllegalArgumentException.class);

        ProcessTask c1 = engine.claim(new ProcessTaskClaimCommand(
                "tenant-a", t1.id(), "r1", Set.of("REVIEWER"), t1.version()));
        ProcessTask done1 = engine.complete(new ProcessTaskCompletionCommand(
                "tenant-a", c1.id(), "NEXT", "r1", "ok", c1.version(), "c-1"));
        assertThat(done1.completionEvent()).isEqualTo("NEXT");
        assertThat(engine.complete(new ProcessTaskCompletionCommand(
                "tenant-a", c1.id(), "NEXT", "r1", "ok", c1.version(), "c-1"))).isEqualTo(done1);
        assertThatThrownBy(() -> engine.complete(new ProcessTaskCompletionCommand(
                "tenant-a", c1.id(), "NEXT", "r1", "diff", c1.version(), "c-1")))
                .isInstanceOf(IllegalArgumentException.class);

        ProcessTask t2 = engine.taskFor(instance.id());
        ProcessTask c2 = engine.claim(new ProcessTaskClaimCommand(
                "tenant-a", t2.id(), "approver", Set.of(), t2.version()));
        engine.complete(new ProcessTaskCompletionCommand(
                "tenant-a", c2.id(), "APPROVE", "approver", "done", c2.version(), "c-2"));

        assertThatThrownBy(() -> engine.taskFor(instance.id())).isInstanceOf(IllegalArgumentException.class);
        ProcessInstance restarted = engine.start(new ProcessInstanceCommand(
                "tenant-a", "review", "order", "o-1", "K1", Map.of(), "req-3", "u0"));
        assertThat(restarted.id()).isNotEqualTo(instance.id());
    }

    @Test
    void shouldValidatePrefixDuplicateDefinitionAndDirectEnd() {
        assertThatThrownBy(() -> new RedisProcessEngine(redis, new ObjectMapper(), " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisProcessEngine(redis, new ObjectMapper(), "bad prefix!"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.register(definition()))
                .isInstanceOf(IllegalArgumentException.class);

        engine.register(new ProcessDefinition(null, "fast-end", 1,
                List.of(new ProcessNode("s", ProcessNodeType.START), new ProcessNode("e", ProcessNodeType.END)),
                List.of(new ProcessTransition("s", "START", "e"))));
        ProcessInstance completed = engine.start(new ProcessInstanceCommand(
                "tenant-x", "fast-end", "doc", "d-1", null, Map.of(), "req-fast", "u1"));
        assertThat(completed.status()).isEqualTo(ProcessInstanceStatus.COMPLETED);
    }

    private static ProcessDefinition definition() {
        return new ProcessDefinition("tenant-a", "review", 1,
                List.of(new ProcessNode("start", ProcessNodeType.START),
                        new ProcessNode("step1", ProcessNodeType.USER_TASK, Set.of(), Set.of("REVIEWER"), Duration.ofMinutes(5)),
                        new ProcessNode("step2", ProcessNodeType.USER_TASK),
                        new ProcessNode("end", ProcessNodeType.END)),
                List.of(new ProcessTransition("start", "START", "step1"),
                        new ProcessTransition("step1", "NEXT", "step2"),
                        new ProcessTransition("step2", "APPROVE", "end")));
    }
}
