package cd.lan1akea.core.hook.impl;

import cd.lan1akea.core.agent.loop.LoopContext;
import cd.lan1akea.core.context.RuntimeContext;
import cd.lan1akea.core.hook.HookContext;
import cd.lan1akea.core.hook.HookEvent;
import cd.lan1akea.core.hook.HookEventType;
import cd.lan1akea.core.hook.HookResult;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.UserMessage;
import cd.lan1akea.core.message.AssistantMessage;
import cd.lan1akea.core.session.Session;
import cd.lan1akea.core.session.SessionId;
import cd.lan1akea.core.session.SessionState;
import cd.lan1akea.core.state.InMemoryAgentStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SessionPersistenceHook 单元测试。
 * 验证 AFTER_ITERATION 事件触发时：
 * 1. 对话轮次持久化到 AgentStateStore
 * 2. 执行检查点保存
 * 3. 边界条件：无 loopContext / 无 stateStore / 无 sessionId
 */
class SessionPersistenceHookTest {

    private InMemoryAgentStateStore stateStore;
    private SessionPersistenceHook hook;

    @BeforeEach
    void setUp() {
        stateStore = new InMemoryAgentStateStore();
        hook = new SessionPersistenceHook(stateStore);
    }

    // ========================================================================
    // 基础功能
    // ========================================================================

    @Test
    void shouldSubscribeToAfterIteration() {
        assertTrue(hook.getSubscribedEventTypes().contains(HookEventType.AFTER_ITERATION));
        assertEquals(1, hook.getSubscribedEventTypes().size());
    }

    @Test
    void shouldPersistTurnAndCheckpoint() {
        // 准备：创建一次性 Session（addTurn 会兜底 auto-create，但显式 create 更干净）
        stateStore.create(new Session(new SessionId("s1"), "t1", "agent",
            SessionState.ACTIVE, null, null, null)).block();

        // 构建包含用户和助手消息的 LoopContext
        List<Msg> messages = List.of(
            UserMessage.of("hello"),
            AssistantMessage.of("hi there")
        );
        LoopContext ctx = LoopContext.builder()
            .agentName("agent")
            .sessionId("s1")
            .tenantId("t1")
            .messages(messages)
            .build();
        ctx.setIteration(1);

        // 触发 Hook
        HookEvent event = new HookEvent(HookEventType.AFTER_ITERATION);
        event.setPayload("loopContext", ctx);
        HookContext hookCtx = hookContext("s1");

        HookResult result = hook.onEvent(event, hookCtx).block();
        assertFalse(result.isAbort());
        assertFalse(result.isInterrupt());

        // 验证 turn 已持久化
        List<Msg> history = stateStore.getHistory(new SessionId("s1")).collectList().block();
        assertNotNull(history);
        assertEquals(2, history.size());
        assertTrue(history.stream().anyMatch(m -> m.getTextContent().contains("hello")));
        assertTrue(history.stream().anyMatch(m -> m.getTextContent().contains("hi there")));

        // 验证 checkpoint 已保存
        var checkpoint = stateStore.loadLatestCheckpoint("s1").block();
        assertNotNull(checkpoint);
        assertEquals("agent", checkpoint.getAgentName());
        assertEquals(1, checkpoint.getIteration());
    }

    @Test
    void shouldPersistMultipleTurnsAcrossIterations() {
        stateStore.create(new Session(new SessionId("s1"), "t1", "agent",
            SessionState.ACTIVE, null, null, null)).block();

        // 第一轮
        LoopContext ctx1 = LoopContext.builder()
            .agentName("agent").sessionId("s1").tenantId("t1")
            .messages(List.of(UserMessage.of("q1"), AssistantMessage.of("a1")))
            .build();
        ctx1.setIteration(1);
        HookEvent e1 = new HookEvent(HookEventType.AFTER_ITERATION);
        e1.setPayload("loopContext", ctx1);
        hook.onEvent(e1, hookContext("s1")).block();

        // 第二轮 — 消息累积（模拟 ReAct 循环的行为）
        LoopContext ctx2 = LoopContext.builder()
            .agentName("agent").sessionId("s1").tenantId("t1")
            .messages(List.of(UserMessage.of("q1"), AssistantMessage.of("a1"),
                              UserMessage.of("q2"), AssistantMessage.of("a2")))
            .build();
        ctx2.setIteration(2);
        HookEvent e2 = new HookEvent(HookEventType.AFTER_ITERATION);
        e2.setPayload("loopContext", ctx2);
        hook.onEvent(e2, hookContext("s1")).block();

        // 应有 2 个 turn
        Session session = stateStore.findById(new SessionId("s1")).block();
        assertNotNull(session);
        assertEquals(2, session.getTurnCount());
    }

    // ========================================================================
    // 边界条件
    // ========================================================================

    @Test
    void shouldDoNothingWhenLoopContextIsNull() {
        HookEvent event = new HookEvent(HookEventType.AFTER_ITERATION);
        // 不 set loopContext
        HookResult result = hook.onEvent(event, hookContext("s1")).block();
        assertFalse(result.isAbort());
        assertFalse(result.isInterrupt());

        List<Msg> history = stateStore.getHistory(new SessionId("s1")).collectList().block();
        assertTrue(history.isEmpty());
    }

    @Test
    void shouldDoNothingWhenStateStoreIsNull() {
        SessionPersistenceHook nullStoreHook = new SessionPersistenceHook(null);
        LoopContext ctx = LoopContext.builder()
            .agentName("agent").sessionId("s1")
            .messages(List.of(UserMessage.of("hello"))).build();
        HookEvent event = new HookEvent(HookEventType.AFTER_ITERATION);
        event.setPayload("loopContext", ctx);

        // 不应抛异常
        HookResult result = nullStoreHook.onEvent(event, hookContext("s1")).block();
        assertFalse(result.isAbort());
        assertFalse(result.isInterrupt());
    }

    @Test
    void shouldDoNothingWhenSessionIdIsNull() {
        stateStore.create(new Session(new SessionId("s1"), "t1", "agent",
            SessionState.ACTIVE, null, null, null)).block();

        LoopContext ctx = LoopContext.builder()
            .agentName("agent").sessionId(null)
            .messages(List.of(UserMessage.of("hello"))).build();
        HookEvent event = new HookEvent(HookEventType.AFTER_ITERATION);
        event.setPayload("loopContext", ctx);
        HookContext hookCtx = hookContext(null);

        HookResult result = hook.onEvent(event, hookCtx).block();
        assertFalse(result.isAbort());
        assertFalse(result.isInterrupt());

        // 不应创建新的 session
        List<Msg> history = stateStore.getHistory(new SessionId("s1")).collectList().block();
        assertTrue(history.isEmpty());
    }

    @Test
    void shouldPersistStructuredMessagesPreservingContentBlocks() {
        stateStore.create(new Session(new SessionId("s1"), "t1", "agent",
            SessionState.ACTIVE, null, null, null)).block();

        // 构建带 ContentBlock 的消息（模拟 ToolUse / Text 混合）
        Msg userMsg = UserMessage.of("search for AI");
        Msg assistantMsg = AssistantMessage.builder()
            .addToolUse("tool_1", "search", "{\"q\":\"AI\"}")
            .addText("Let me search...")
            .build();

        LoopContext ctx = LoopContext.builder()
            .agentName("agent").sessionId("s1").tenantId("t1")
            .messages(List.of(userMsg, assistantMsg))
            .build();
        ctx.setIteration(1);
        HookEvent event = new HookEvent(HookEventType.AFTER_ITERATION);
        event.setPayload("loopContext", ctx);
        hook.onEvent(event, hookContext("s1")).block();

        // 验证结构保留
        List<Msg> history = stateStore.getHistory(new SessionId("s1")).collectList().block();
        assertEquals(2, history.size());
        Msg restoredAssistant = history.get(1);
        assertFalse(restoredAssistant.getToolUseBlocks().isEmpty());
        assertEquals("search", restoredAssistant.getToolUseBlocks().get(0).getName());
        assertFalse(restoredAssistant.getContentBlocks().stream()
            .filter(b -> "text".equals(b.getType())).findAny().isEmpty());
    }

    @Test
    void shouldAutoCreateSessionIfNotExists() {
        // addTurn 兜底：不显式 create，直接触发 hook
        LoopContext ctx = LoopContext.builder()
            .agentName("agent").sessionId("auto-s1").tenantId("t1")
            .messages(List.of(UserMessage.of("first msg"), AssistantMessage.of("first reply")))
            .build();
        ctx.setIteration(1);
        HookEvent event = new HookEvent(HookEventType.AFTER_ITERATION);
        event.setPayload("loopContext", ctx);
        hook.onEvent(event, hookContext("auto-s1")).block();

        List<Msg> history = stateStore.getHistory(new SessionId("auto-s1")).collectList().block();
        assertEquals(2, history.size());
    }

    @Test
    void shouldReturnContinueToNotBlockHookChain() {
        LoopContext ctx = LoopContext.builder()
            .agentName("agent").sessionId("s1")
            .messages(List.of(UserMessage.of("ok"))).build();
        HookEvent event = new HookEvent(HookEventType.AFTER_ITERATION);
        event.setPayload("loopContext", ctx);

        HookResult result = hook.onEvent(event, hookContext("s1")).block();
        assertFalse(result.isAbort());
        assertFalse(result.isInterrupt());
    }

    @Test
    void shouldBeLowestPriority() {
        assertTrue(hook.getPriority() > 0, "persistence hook should have low priority (run last)");
    }

    // ========================================================================
    // helper
    // ========================================================================

    private static HookContext hookContext(String sessionId) {
        return new HookContext("agent", "t1", sessionId, "u1", 0, List.of(), null);
    }
}
