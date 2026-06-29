package cd.lan1akea.harness.hook;

import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.MsgRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeBaseHookTest {

    @Test
    void testNameAndPriority() {
        KnowledgeBaseHook hook = new KnowledgeBaseHook((q, ctx) -> "answer");
        assertEquals("KnowledgeBaseHook", hook.getName());
        assertEquals(90, hook.getPriority());
    }

    @Test
    void testSubscribesToPreReasoning() {
        KnowledgeBaseHook hook = new KnowledgeBaseHook((q, ctx) -> "answer");
        var types = hook.getSubscribedEventTypes();
        assertTrue(types.contains(HookEventType.PRE_REASONING));
        assertEquals(1, types.size());
    }

    @Test
    void testMatchSetsBypassMessage() {
        KnowledgeBaseHook hook = new KnowledgeBaseHook(
            (query, ctx) -> "你好".equals(query) ? "你好！有什么可以帮您？" : null);
        ReasoningEvent event = new ReasoningEvent(HookEventType.PRE_REASONING);
        Msg userMsg = Msg.builder(MsgRole.USER).addText("你好").build();
        event.setMessages(List.of(userMsg));

        HookResult result = hook.onEvent(event,
            new HookContext("agent", "t1", "s1", "u1", 0, null, null)).block();

        assertTrue(result.isContinue());
        assertNotNull(event.getBypassMessage());
        assertEquals(MsgRole.ASSISTANT, event.getBypassMessage().getRole());
        assertEquals("你好！有什么可以帮您？", event.getBypassMessage().getTextContent());
    }

    @Test
    void testNoMatchDoesNotSetBypass() {
        KnowledgeBaseHook hook = new KnowledgeBaseHook((q, ctx) -> null);
        ReasoningEvent event = new ReasoningEvent(HookEventType.PRE_REASONING);
        Msg userMsg = Msg.builder(MsgRole.USER).addText("随机问题").build();
        event.setMessages(List.of(userMsg));

        HookResult result = hook.onEvent(event,
            new HookContext("agent", "t1", "s1", "u1", 0, null, null)).block();

        assertTrue(result.isContinue());
        assertNull(event.getBypassMessage());
    }

    @Test
    void testNullInputDoesNotSetBypass() {
        KnowledgeBaseHook hook = new KnowledgeBaseHook((q, ctx) -> "answer");
        ReasoningEvent event = new ReasoningEvent(HookEventType.PRE_REASONING);
        event.setMessages(null);

        HookResult result = hook.onEvent(event,
            new HookContext("agent", "t1", "s1", "u1", 0, null, null)).block();

        assertTrue(result.isContinue());
        assertNull(event.getBypassMessage());
    }

    @Test
    void testHookContextPassedToMatcher() {
        final HookContext[] captured = {null};
        KnowledgeBaseHook hook = new KnowledgeBaseHook((q, ctx) -> { captured[0] = ctx; return null; });
        ReasoningEvent event = new ReasoningEvent(HookEventType.PRE_REASONING);
        Msg userMsg = Msg.builder(MsgRole.USER).addText("hi").build();
        event.setMessages(List.of(userMsg));
        HookContext hc = new HookContext("agent", "T1", "S1", "U1", 0, null, null);

        hook.onEvent(event, hc).block();

        assertNotNull(captured[0]);
        assertEquals("T1", captured[0].getTenantId());
        assertEquals("U1", captured[0].getUserId());
        assertEquals("S1", captured[0].getSessionId());
    }

    @Test
    void testMatcherExceptionAborts() {
        KnowledgeBaseHook hook = new KnowledgeBaseHook((q, ctx) -> { throw new RuntimeException("boom"); });
        ReasoningEvent event = new ReasoningEvent(HookEventType.PRE_REASONING);
        Msg userMsg = Msg.builder(MsgRole.USER).addText("hi").build();
        event.setMessages(List.of(userMsg));

        HookResult result = hook.onEvent(event,
            new HookContext("agent", "t1", "s1", "u1", 0, null, null)).block();

        assertTrue(result.isAbort());
        assertTrue(result.getAbortReason().contains("boom"));
    }

    @Test
    void testCustomName() {
        KnowledgeBaseHook hook = new KnowledgeBaseHook("MyKB", (q, ctx) -> "answer");
        assertEquals("MyKB", hook.getName());
    }

    @Test
    void testOtherEventTypeDoesNothing() {
        KnowledgeBaseHook hook = new KnowledgeBaseHook((q, ctx) -> "answer");
        HookResult result = hook.onEvent(new HookEvent(HookEventType.PRE_TOOL_CALL),
            new HookContext("a", "t", "s", "u", 0, null, null)).block();
        assertTrue(result.isContinue());
    }
}
