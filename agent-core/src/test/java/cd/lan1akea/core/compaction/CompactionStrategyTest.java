package cd.lan1akea.core.compaction;

import cd.lan1akea.core.message.*;
import cd.lan1akea.core.model.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;


import static org.junit.jupiter.api.Assertions.*;

class CompactionStrategyTest {

    private final ModelContextWindow window = new ModelContextWindow("test", 8000, 4000);
    private final CompactionContext ctx = CompactionContext.builder()
        .maxInputTokens(8000).keepRecent(4).build();

    // ========================================================================
    // SnipCompactionStrategy
    // ========================================================================

    @Test
    void snipRemovesEmptyToolResults() {
        List<Msg> msgs = new ArrayList<>();
        msgs.add(UserMessage.of("search for Java"));
        // assistant WITH text + tool_use → 保留
        msgs.add(Msg.builder(MsgRole.ASSISTANT).addText("searching...")
            .addToolUse("t1","search","{\"q\":\"Java\"}").build());
        msgs.add(Msg.builder(MsgRole.TOOL).addToolResult("t1", "", false).build()); // empty → 删除
        msgs.add(Msg.builder(MsgRole.ASSISTANT).addText("找到了").build());

        SnipCompactionStrategy snip = new SnipCompactionStrategy();
        List<Msg> result = snip.compact(msgs, ctx).block();

        assertEquals(3, result.size(), "空 tool_result 应被删除（assistant 有文本应保留）");
    }

    @Test
    void snipRemovesToolOnlyAssistant() {
        List<Msg> msgs = new ArrayList<>();
        msgs.add(UserMessage.of("calc 1+1"));
        // assistant with ONLY tool_calls, no text → 删除
        msgs.add(Msg.builder(MsgRole.ASSISTANT)
            .addToolUse("t1", "calculator", "{\"expr\":\"1+1\"}").build());
        msgs.add(Msg.builder(MsgRole.TOOL).addToolResult("t1", "2", false).build());
        msgs.add(Msg.builder(MsgRole.ASSISTANT).addText("结果是2").build());

        SnipCompactionStrategy snip = new SnipCompactionStrategy();
        List<Msg> result = snip.compact(msgs, ctx).block();

        // assistant(tool_only) 删除 + system 合并 → 2 条
        assertEquals(2, result.size(), "删除无文本的 assistant tool_call 消息");
        assertEquals("calc 1+1", result.get(0).getTextContent());
        assertEquals("结果是2", result.get(1).getTextContent());
    }

    @Test
    void snipShouldCompactTrigger() {
        SnipCompactionStrategy snip = new SnipCompactionStrategy();
        assertFalse(snip.shouldCompact(List.of(UserMessage.of("hi")), 100, 8000));

        String longText = "x".repeat(7000);
        assertTrue(snip.shouldCompact(
            List.of(UserMessage.of(longText),
                    Msg.builder(MsgRole.ASSISTANT).addText(longText).build(),
                    UserMessage.of(longText),
                    Msg.builder(MsgRole.ASSISTANT).addText(longText).build(),
                    UserMessage.of(longText)),
            7000, 8000));
    }

    // ========================================================================
    // TrimCompactionStrategy
    // ========================================================================

    @Test
    void trimKeepsRecentMessages() {
        List<Msg> msgs = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            msgs.add(UserMessage.of("msg" + i));
            msgs.add(Msg.builder(MsgRole.ASSISTANT).addText("reply" + i).build());
        }
        assertEquals(20, msgs.size());

        TrimCompactionStrategy trim = new TrimCompactionStrategy();
        CompactionContext ctx4 = CompactionContext.builder()
            .maxInputTokens(8000).keepRecent(4).build();

        List<Msg> result = trim.compact(msgs, ctx4).block();
        assertEquals(4, result.size());
        assertEquals("msg9", result.get(0).getTextContent());
    }

    @Test
    void trimNoOpWhenUnderKeepRecent() {
        List<Msg> msgs = List.of(UserMessage.of("hi"));
        TrimCompactionStrategy trim = new TrimCompactionStrategy();

        List<Msg> result = trim.compact(msgs, ctx).block();
        assertEquals(1, result.size());
    }

    // ========================================================================
    // ProgressiveCompactionStrategy
    // ========================================================================

    @Test
    void progressiveChainsStrategies() {
        // 造消息：有废弃内容 + 大量消息超限
        List<Msg> msgs = new ArrayList<>();
        msgs.add(UserMessage.of("task 1"));
        msgs.add(Msg.builder(MsgRole.ASSISTANT).addToolUse("t1","x","{}").build());
        msgs.add(Msg.builder(MsgRole.TOOL).addToolResult("t1","",false).build()); // empty
        String longText = "x".repeat(1000);
        for (int i = 0; i < 15; i++) {
            msgs.add(UserMessage.of("msg" + i + " " + longText));
            msgs.add(Msg.builder(MsgRole.ASSISTANT).addText("reply" + i + " " + longText).build());
        }

        ModelContextWindow w = new ModelContextWindow("test", 8000, 4000);
        CompactionContext pCtx = CompactionContext.builder()
            .maxInputTokens(8000).keepRecent(4).build();

        ProgressiveCompactionStrategy progressive = new ProgressiveCompactionStrategy(w,
            new SnipCompactionStrategy(),
            new TrimCompactionStrategy());

        List<Msg> result = progressive.compact(msgs, pCtx).block();

        assertNotNull(result);
        // Snip 先删掉空 tool_result，Trim 再保留最近 4 条
        assertTrue(result.size() <= msgs.size(),
            "压缩后不应比原来大: " + result.size() + " vs " + msgs.size());
    }

    @Test
    void progressiveShouldCompactDelegates() {
        ModelContextWindow w = new ModelContextWindow("test", 8000, 4000);
        ProgressiveCompactionStrategy p = new ProgressiveCompactionStrategy(w,
            new TrimCompactionStrategy());

        String longText = "x".repeat(7000);
        List<Msg> msgs = List.of(
            UserMessage.of(longText),
            Msg.builder(MsgRole.ASSISTANT).addText(longText).build(),
            UserMessage.of(longText),
            Msg.builder(MsgRole.ASSISTANT).addText(longText).build(),
            UserMessage.of(longText));

        assertTrue(p.shouldCompact(msgs, 7000, 8000));
    }

    // ========================================================================
    // SummaryCompactionStrategy
    // ========================================================================

    @Test
    void summaryFallbackWithoutModel() {
        List<Msg> msgs = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            msgs.add(UserMessage.of("user question " + i));
            msgs.add(Msg.builder(MsgRole.ASSISTANT).addText("assistant answer " + i).build());
        }

        SummaryCompactionStrategy summary = new SummaryCompactionStrategy();
        List<Msg> result = summary.compact(msgs, ctx).block();

        assertNotNull(result);
        // 保留 keepRecent 条 + 1 条摘要
        assertEquals(5, result.size(), "应保留 4 条近期 + 1 条摘要");
        assertEquals(MsgRole.SYSTEM, result.get(0).getRole(), "第一条应为摘要");
    }

    @Test
    void summaryTriggerThreshold() {
        SummaryCompactionStrategy s = new SummaryCompactionStrategy();
        assertFalse(s.shouldCompact(List.of(UserMessage.of("hi")), 100, 8000));

        String longText = "x".repeat(7000);
        assertTrue(s.shouldCompact(
            List.of(UserMessage.of(longText),
                    Msg.builder(MsgRole.ASSISTANT).addText(longText).build(),
                    UserMessage.of(longText),
                    Msg.builder(MsgRole.ASSISTANT).addText(longText).build(),
                    UserMessage.of(longText)),
            7000, 8000));
    }

    @Test
    void summaryWithModelGeneratesStructuredOutput() {
        // Mock 模型返回结构化摘要
        ChatModel mockModel = new MockModel("mock", "mock-model",
            "[模型蒸馏] 目标: 搜索Java资料 | 进度: 已完成10轮对话 | 下一步: 继续搜索");

        SummaryCompactionStrategy summary = new SummaryCompactionStrategy();
        CompactionContext ctxWithModel = CompactionContext.builder()
            .maxInputTokens(8000).keepRecent(4).model(mockModel)
            .generateOptions(GenerateOptions.builder().maxTokens(512).build())
            .build();

        List<Msg> msgs = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            msgs.add(UserMessage.of("question " + i));
            msgs.add(Msg.builder(MsgRole.ASSISTANT).addText("answer " + i).build());
        }

        List<Msg> result = summary.compact(msgs, ctxWithModel).block();

        assertNotNull(result);
        assertEquals(5, result.size(), "4 条近期 + 1 条 LLM 摘要");
        assertEquals(MsgRole.SYSTEM, result.get(0).getRole());
        String summaryText = result.get(0).getTextContent();
        assertTrue(summaryText.contains("Java"), "摘要应包含关键信息");
        assertTrue(summaryText.contains("目标") || summaryText.contains("进度"),
            "摘要应包含结构化字段");
    }

    // ========================================================================
    // Snip: 连续 system 消息合并
    // ========================================================================

    @Test
    void snipMergesConsecutiveSystemMessages() {
        List<Msg> msgs = new ArrayList<>();
        msgs.add(SystemMessage.of("system prompt v1"));
        msgs.add(SystemMessage.of("system prompt v2")); // 重复 → 合并保留 v2
        msgs.add(UserMessage.of("hello"));
        msgs.add(Msg.builder(MsgRole.ASSISTANT).addText("hi").build());

        SnipCompactionStrategy snip = new SnipCompactionStrategy();
        List<Msg> result = snip.compact(msgs, ctx).block();

        assertEquals(3, result.size(), "重复 system 应合并为 1 条");
        assertEquals("system prompt v2", result.get(0).getTextContent(),
            "保留最后一个 system 消息");
    }

    @Test
    void snipKeepsNonConsecutiveSystemMessages() {
        List<Msg> msgs = new ArrayList<>();
        msgs.add(SystemMessage.of("system A"));
        msgs.add(UserMessage.of("hello"));
        msgs.add(SystemMessage.of("system B")); // 不连续 → 保留
        msgs.add(Msg.builder(MsgRole.ASSISTANT).addText("reply").build());

        SnipCompactionStrategy snip = new SnipCompactionStrategy();
        List<Msg> result = snip.compact(msgs, ctx).block();

        assertEquals(4, result.size(), "不连续的 system 消息应全部保留");
    }

    // ========================================================================
    // Progressive: 逐级验证 Snip→Trim 顺序
    // ========================================================================

    @Test
    void progressiveRunsSnipBeforeTrim() {
        // 构造消息：有空 tool_result + 超长历史
        // Snip 先删 tool_result，Trim 再裁剪长度
        List<Msg> msgs = new ArrayList<>();
        msgs.add(SystemMessage.of("sys"));
        msgs.add(UserMessage.of("task"));
        // 空 tool_result — Snip 应该删除
        msgs.add(Msg.builder(MsgRole.ASSISTANT)
            .addToolUse("t1", "search", "{}").build());
        msgs.add(Msg.builder(MsgRole.TOOL).addToolResult("t1", "", false).build());
        // 大量消息 — Trim 应该裁剪
        String longText = "y".repeat(500);
        for (int i = 0; i < 20; i++) {
            msgs.add(UserMessage.of("msg" + i + " " + longText));
            msgs.add(Msg.builder(MsgRole.ASSISTANT).addText("reply" + i).build());
        }

        AtomicInteger snipCount = new AtomicInteger(0);
        AtomicInteger trimCount = new AtomicInteger(0);

        CompactionStrategy trackingSnip = new CompactionStrategy() {
            @Override public String getName() { return "track-snip"; }
            @Override public boolean shouldCompact(List<Msg> m, int e, int max) { return true; }
            @Override public Mono<List<Msg>> compact(List<Msg> m, CompactionContext c) {
                snipCount.incrementAndGet();
                // 模拟 Snip：删除空 tool_result
                List<Msg> filtered = new ArrayList<>();
                for (Msg msg : m) {
                    if (msg.getRole() == MsgRole.TOOL) {
                        String content = msg.getTextContent();
                        if (content == null || content.isBlank()) continue;
                    }
                    if (msg.getRole() == MsgRole.ASSISTANT
                        && msg.getToolUseBlocks() != null && !msg.getToolUseBlocks().isEmpty()
                        && (msg.getTextContent() == null || msg.getTextContent().isBlank())) {
                        continue;
                    }
                    filtered.add(msg);
                }
                return Mono.just(filtered);
            }
        };

        CompactionStrategy trackingTrim = new CompactionStrategy() {
            @Override public String getName() { return "track-trim"; }
            @Override public boolean shouldCompact(List<Msg> m, int e, int max) { return true; }
            @Override public Mono<List<Msg>> compact(List<Msg> m, CompactionContext c) {
                trimCount.incrementAndGet();
                int keep = c.getKeepRecent();
                if (m.size() <= keep) return Mono.just(m);
                return Mono.just(new ArrayList<>(m.subList(m.size() - keep, m.size())));
            }
        };

        ModelContextWindow w = new ModelContextWindow("test", 8000, 4000);
        ProgressiveCompactionStrategy progressive = new ProgressiveCompactionStrategy(w,
            trackingSnip, trackingTrim);

        CompactionContext pCtx = CompactionContext.builder()
            .maxInputTokens(8000).keepRecent(4).build();
        List<Msg> result = progressive.compact(msgs, pCtx).block();

        assertNotNull(result);
        assertEquals(1, snipCount.get(), "Snip 应执行 1 次");
        assertEquals(1, trimCount.get(), "Trim 应执行 1 次");
        assertTrue(result.size() <= 4, "最终保留 ≤ 4 条: 实际 " + result.size());
    }

    @Test
    void progressiveSkipsStrategyIfNotNeeded() {
        AtomicInteger trimCount = new AtomicInteger(0);

        CompactionStrategy conditionalTrim = new CompactionStrategy() {
            @Override public String getName() { return "cond-trim"; }
            @Override public boolean shouldCompact(List<Msg> m, int e, int max) {
                return m.size() > 10; // 只有超过 10 条才触发
            }
            @Override public Mono<List<Msg>> compact(List<Msg> m, CompactionContext c) {
                trimCount.incrementAndGet();
                return Mono.just(m.subList(m.size() - 4, m.size()));
            }
        };

        ModelContextWindow w = new ModelContextWindow("test", 8000, 4000);
        ProgressiveCompactionStrategy progressive = new ProgressiveCompactionStrategy(w,
            conditionalTrim);

        List<Msg> shortMsgs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            shortMsgs.add(UserMessage.of("msg" + i));
            shortMsgs.add(Msg.builder(MsgRole.ASSISTANT).addText("reply" + i).build());
        }

        CompactionContext pCtx = CompactionContext.builder()
            .maxInputTokens(8000).keepRecent(4).build();
        progressive.compact(shortMsgs, pCtx).block();

        assertEquals(0, trimCount.get(), "消息不足 10 条时不应触发压缩");
    }

    // ========================================================================
    // 端到端：ReActLoop 超长对话触发压缩
    // ========================================================================

    @Test
    void endToEndCompactionViaHookChain() {
        // 模拟 ReActLoop dispatch: 构造 ReasoningEvent + 设置 messages
        AtomicInteger compactionCount = new AtomicInteger(0);

        CompactionStrategy countingStrategy = new CompactionStrategy() {
            @Override public String getName() { return "count-test"; }
            @Override public boolean shouldCompact(List<Msg> m, int e, int max) {
                return m.size() > 4;
            }
            @Override public Mono<List<Msg>> compact(List<Msg> m, CompactionContext c) {
                compactionCount.incrementAndGet();
                int keep = Math.min(c.getKeepRecent(), m.size());
                return Mono.just(new ArrayList<>(m.subList(m.size() - keep, m.size())));
            }
        };

        cd.lan1akea.core.hook.impl.ContextCompressionHook hook =
            new cd.lan1akea.core.hook.impl.ContextCompressionHook(
                countingStrategy,
                new ModelContextWindow("test", 8000, 4000),
                CompactionContext.builder().maxInputTokens(8000).keepRecent(4).build());

        cd.lan1akea.core.hook.HookChain chain = new cd.lan1akea.core.hook.HookChain();
        chain.register(hook);

        // 构造超长对话
        List<Msg> messages = new ArrayList<>();
        messages.add(SystemMessage.of("你是一个助手"));
        String fill = "x".repeat(200);
        for (int i = 0; i < 10; i++) {
            messages.add(UserMessage.of("turn " + i + " " + fill));
            messages.add(Msg.builder(MsgRole.ASSISTANT).addText("reply " + i).build());
        }
        messages.add(UserMessage.of("总结一下"));

        // 模拟 ReActLoop 的 PRE_REASONING dispatch
        cd.lan1akea.core.hook.ReasoningEvent event =
            new cd.lan1akea.core.hook.ReasoningEvent(cd.lan1akea.core.hook.HookEventType.PRE_REASONING);
        event.setMessages(messages);

        cd.lan1akea.core.hook.HookContext hc = new cd.lan1akea.core.hook.HookContext(
            "test", "t", "s", "u", 5, List.of(), null);

        cd.lan1akea.core.hook.HookResult result = chain.fire(
            cd.lan1akea.core.hook.HookEventType.PRE_REASONING, event, hc).block();

        assertNotNull(result);
        assertTrue(result.isModify(), "压缩应返回 MODIFY");
        assertTrue(compactionCount.get() >= 1,
            "超长对话应触发至少 1 次压缩，实际: " + compactionCount.get());
    }

    // ========================================================================
    // Mock Model
    // ========================================================================

    static class MockModel extends ChatModelBase {
        private final String reply;

        MockModel(String provider, String modelName, String reply) {
            super(provider, modelName, new cd.lan1akea.core.formatter.OpenAiMessageFormatter());
            this.reply = reply;
        }

        @Override protected Map<String, String> buildAuthHeaders() { return Map.of(); }
        @Override protected String buildApiUrl() { return "http://localhost/mock"; }

        @Override
        protected Mono<ChatResponse> doChat(List<Map<String, Object>> messages,
                                             List<ToolSchema> toolSchemas,
                                             GenerateOptions options) {
            Msg msg = cd.lan1akea.core.message.AssistantMessage.of(reply);
            return Mono.just(new ChatResponse(msg, new ChatUsage(1, 1), "stop", "mock"));
        }

        @Override
        protected Flux<ChatStreamChunk> doStream(List<Map<String, Object>> messages,
                                                   List<ToolSchema> toolSchemas,
                                                   GenerateOptions options) {
            return Flux.just(ChatStreamChunk.builder().delta(reply).type(ChatStreamChunk.TYPE_TEXT).build());
        }
    }
}
