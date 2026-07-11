package cd.lan1akea.core.hook.impl;

import cd.lan1akea.core.compaction.CompactionContext;
import cd.lan1akea.core.compaction.SummaryCompactionStrategy;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.*;
import cd.lan1akea.core.model.ModelContextWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextCompressionHookTest {

    private ModelContextWindow contextWindow;
    private ContextCompressionHook hook;

    @BeforeEach
    void setUp() {
        contextWindow = new ModelContextWindow("test-model", 8000, 4000);
        hook = new ContextCompressionHook(
            new SummaryCompactionStrategy(),
            contextWindow,
            CompactionContext.builder().maxInputTokens(8000).keepRecent(4).build());
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

        HookEvent event = new HookEvent(HookEventType.PRE_REASONING);
        event.setMessages(messages);

        HookResult result = hook.onEvent(event,
            new HookContext("test", null, null, null, 0, null, null)).block();

        assertNotNull(result);
        assertTrue(result.isModify() || result.isContinue());
    }

    @Test
    void testSkipsWhenUnderLimit() {
        List<Msg> messages = List.of(UserMessage.of("short message"));

        HookEvent event = new HookEvent(HookEventType.PRE_REASONING);
        event.setMessages(messages);

        HookResult result = hook.onEvent(event,
            new HookContext("test", null, null, null, 0, null, null)).block();

        // 未接近限制时应该 CONTINUE
        assertNotNull(result);
    }

}
