package cd.lan1akea.core.hook.impl;

import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.MsgRole;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeBaseHookTest {

    @Test
    void testNameAndPriority() {
        KnowledgeBaseHook hook = new KnowledgeBaseHook(u -> "answer");
        assertEquals("KnowledgeBaseHook", hook.getName());
        assertEquals(90, hook.getPriority());
    }

    @Test
    void testSubscribesToPreReasoning() {
        KnowledgeBaseHook hook = new KnowledgeBaseHook(u -> "answer");
        Set<HookEventType> types = hook.getSubscribedEventTypes();
        assertTrue(types.contains(HookEventType.PRE_REASONING));
        assertEquals(1, types.size());
    }

    @Test
    void testMatchSetsBypassMessage() {
        KnowledgeBaseHook hook = new KnowledgeBaseHook(u -> "你好".equals(u) ? "你好！有什么可以帮您？" : null);
        ReasoningEvent event = new ReasoningEvent(HookEventType.PRE_REASONING);
        Msg userMsg = Msg.builder(MsgRole.USER).addText("你好").build();
        event.setMessages(List.of(userMsg));

        HookResult result = hook.onEvent(event,
            new HookContext("agent", "t1", "s1", "u1", 0, null, null)).block();

        assertTrue(result.isContinue());
        assertNotNull(event.getBypassMessage());
        assertEquals("你好！有什么可以帮您？", event.getBypassMessage().getTextContent());
    }

    @Test
    void testNoMatchDoesNotSetBypass() {
        KnowledgeBaseHook hook = new KnowledgeBaseHook(u -> null);
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
        KnowledgeBaseHook hook = new KnowledgeBaseHook(u -> "answer");
        ReasoningEvent event = new ReasoningEvent(HookEventType.PRE_REASONING);
        event.setMessages(null);

        HookResult result = hook.onEvent(event,
            new HookContext("agent", "t1", "s1", "u1", 0, null, null)).block();

        assertTrue(result.isContinue());
        assertNull(event.getBypassMessage());
    }

    @Test
    void testEmptyMessagesDoesNotSetBypass() {
        KnowledgeBaseHook hook = new KnowledgeBaseHook(u -> "answer");
        ReasoningEvent event = new ReasoningEvent(HookEventType.PRE_REASONING);
        event.setMessages(List.of());

        HookResult result = hook.onEvent(event,
            new HookContext("agent", "t1", "s1", "u1", 0, null, null)).block();

        assertTrue(result.isContinue());
        assertNull(event.getBypassMessage());
    }

    @Test
    void testDifferentEventTypeDoesNothing() {
        KnowledgeBaseHook hook = new KnowledgeBaseHook(u -> "answer");
        HookResult result = hook.onEvent(new HookEvent(HookEventType.PRE_TOOL_CALL),
            new HookContext("agent", "t1", "s1", "u1", 0, null, null)).block();
        assertTrue(result.isContinue());
    }

    @Test
    void testCustomName() {
        KnowledgeBaseHook hook = new KnowledgeBaseHook("MyKB", u -> "answer");
        assertEquals("MyKB", hook.getName());
    }
}
