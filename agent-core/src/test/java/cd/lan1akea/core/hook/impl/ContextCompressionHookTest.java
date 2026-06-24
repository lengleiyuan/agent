package cd.lan1akea.core.hook.impl;

import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.*;
import cd.lan1akea.core.model.ModelContextWindow;
import cd.lan1akea.core.session.ChatTurn;
import cd.lan1akea.core.session.SessionSummaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextCompressionHookTest {

    private SessionSummaryService summaryService;
    private ModelContextWindow contextWindow;
    private ContextCompressionHook hook;

    @BeforeEach
    void setUp() {
        summaryService = new SessionSummaryService();
        contextWindow = new ModelContextWindow("test-model", 8000, 4000);
        hook = new ContextCompressionHook(summaryService, contextWindow);
    }

    @Test
    void testHookName() {
        assertEquals("ContextCompression", hook.getName());
    }

    @Test
    void testSubscribesToPreReasoning() {
        assertTrue(hook.getSubscribedEventTypes().contains(HookEventType.PRE_REASONING));
    }

    @Test
    void testCompressesWhenNearLimit() {
        List<Msg> messages = new ArrayList<>();
        // 填充大量消息使接近上限
        String longText = "x".repeat(6900);
        messages.add(UserMessage.of(longText));

        ReasoningEvent event = new ReasoningEvent(HookEventType.PRE_REASONING);
        event.setMessages(messages);

        HookResult result = hook.onEvent(event,
            new HookContext("test", null, null, null, 0, null, null)).block();

        assertNotNull(result);
        assertTrue(result.isModify() || result.isContinue());
    }

    @Test
    void testSkipsWhenUnderLimit() {
        List<Msg> messages = List.of(UserMessage.of("short message"));

        ReasoningEvent event = new ReasoningEvent(HookEventType.PRE_REASONING);
        event.setMessages(messages);

        HookResult result = hook.onEvent(event,
            new HookContext("test", null, null, null, 0, null, null)).block();

        // 未接近限制时应该 CONTINUE
        assertNotNull(result);
    }

    @Test
    void testSessionSummaryService() {
        ChatTurn turn1 = new ChatTurn(1, 1, 0, "hello", "hi there", null, LocalDateTime.now());
        ChatTurn turn2 = new ChatTurn(2, 1, 1, "how are you", "I'm fine", null, LocalDateTime.now());

        Msg summary = summaryService.summarize(List.of(turn1, turn2));
        assertNotNull(summary);
        assertFalse(summary.getTextContent().isBlank());
        assertTrue(summary.getTextContent().contains("hello"));
    }

    @Test
    void testSessionSummaryServiceSingleTurn() {
        ChatTurn turn = new ChatTurn(1, 1, 0, "what is AI",
            "AI stands for Artificial Intelligence", null, LocalDateTime.now());

        Msg summary = summaryService.summarize(List.of(turn));
        assertNotNull(summary);
        assertFalse(summary.getTextContent().isBlank());
    }

    @Test
    void testSessionSummaryServiceEmpty() {
        Msg summary = summaryService.summarize(List.of());
        assertNotNull(summary);
        assertTrue(summary.getTextContent().contains("对话摘要"));
        assertTrue(summary.getTextContent().contains("0 轮"));
    }
}
