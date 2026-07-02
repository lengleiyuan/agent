package cd.lan1akea.metrics;

import cd.lan1akea.core.metrics.AgentMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MicrometerAgentMetrics 单元测试。
 * 使用 SimpleMeterRegistry 验证所有指标记录方法。
 */
class MicrometerAgentMetricsTest {

    private MeterRegistry registry;
    private MicrometerAgentMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MicrometerAgentMetrics(registry);
    }

    @Test
    void recordLlmCallSuccess() {
        metrics.recordLlmCall("gpt-4", "openai", 1500,
            100, 50, true, null);
        // 不抛异常 = 成功
        assertNotNull(registry.get("agent.llm.call.duration").timer());
        assertEquals(1, registry.get("agent.llm.call").tag("result", "success").counter().count());
    }

    @Test
    void recordLlmCallFailure() {
        metrics.recordLlmCall("gpt-4", "openai", 2000,
            80, 0, false, "timeout");
        assertEquals(1, registry.get("agent.llm.call").tag("result", "failure").counter().count());
    }

    @Test
    void recordLlmCallCountsTokens() {
        metrics.recordLlmCall("gpt-4", "openai", 1000,
            200, 150, true, null);
        assertEquals(200, registry.get("agent.llm.tokens").tag("direction", "prompt").counter().count(), 0.1);
        assertEquals(150, registry.get("agent.llm.tokens").tag("direction", "completion").counter().count(), 0.1);
    }

    @Test
    void recordToolCallSuccess() {
        metrics.recordToolCall("calculator", "LOW", 50,
            true, false, null);
        assertEquals(1, registry.get("agent.tool.call").tag("result", "success").counter().count());
    }

    @Test
    void recordToolCallFailure() {
        metrics.recordToolCall("delete_file", "CRITICAL", 100,
            false, false, "permission denied");
        assertEquals(1, registry.get("agent.tool.call").tag("result", "failure").counter().count());
    }

    @Test
    void recordToolCallTimer() {
        metrics.recordToolCall("echo", "LOW", 75, true, false, null);
        assertNotNull(registry.get("agent.tool.call.duration").timer());
        assertEquals(1, registry.get("agent.tool.call.duration").timer().count());
    }

    @Test
    void recordIteration() {
        metrics.recordIteration("TestAgent", "sess-1", 1, 3);
        assertEquals(1, registry.get("agent.loop.iterations").counter().count());
    }

    @Test
    void recordMultipleIterations() {
        for (int i = 0; i < 5; i++) {
            metrics.recordIteration("TestAgent", "sess-1", i, 2);
        }
        assertEquals(5, registry.get("agent.loop.iterations").counter().count());
    }

    @Test
    void recordApproval() {
        metrics.recordApproval("transfer", "sess-2", "approved");
        assertEquals(1, registry.get("agent.approval").counter().count());
    }

    @Test
    void recordApprovalDenied() {
        metrics.recordApproval("delete_file", "sess-3", "denied");
        assertEquals(1, registry.get("agent.approval").counter().count());
    }

    @Test
    void recordTokenUsage() {
        metrics.recordTokenUsage("TestAgent", "sess-1", "gpt-4", 500, 300);
        assertEquals(500, registry.get("agent.llm.tokens").tag("direction", "prompt").counter().count(), 0.1);
        assertEquals(300, registry.get("agent.llm.tokens").tag("direction", "completion").counter().count(), 0.1);
    }

    @Test
    void agentMetricsNoopDoesNotThrow() {
        AgentMetrics noop = AgentMetrics.NOOP;
        // 所有方法应无异常
        assertDoesNotThrow(() -> noop.recordLlmCall("m", "p", 0, 0, 0, true, null));
        assertDoesNotThrow(() -> noop.recordToolCall("t", "r", 0, true, false, null));
        assertDoesNotThrow(() -> noop.recordIteration("a", "s", 0, 0));
        assertDoesNotThrow(() -> noop.recordApproval("t", "s", "d"));
        assertDoesNotThrow(() -> noop.recordTokenUsage("a", "s", "m", 0, 0));
    }

    @Test
    void mixedSuccessAndFailureCalls() {
        metrics.recordLlmCall("gpt-4", "openai", 100, 10, 5, true, null);
        metrics.recordLlmCall("gpt-4", "openai", 200, 15, 0, false, "500");
        metrics.recordLlmCall("gpt-4", "openai", 150, 12, 4, true, null);

        assertEquals(2, registry.get("agent.llm.call").tag("result", "success").counter().count());
        assertEquals(1, registry.get("agent.llm.call").tag("result", "failure").counter().count());
        // Total prompt tokens: 10 + 15 + 12 = 37
        assertEquals(37, registry.get("agent.llm.tokens").tag("direction", "prompt").counter().count(), 0.1);
    }
}
