package cd.lan1akea.core.agent.loop;
import java.util.Set;

import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.*;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.tool.*;
import cd.lan1akea.core.approval.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReActLoop 完整单元测试。
 * 覆盖推理、工具调用、流式、中断、边界条件。
 */
class ReActLoopTest {

    private ReActLoop loop;
    private ToolRegistry toolRegistry;
    private StubChatModel model;

    @BeforeEach
    void setUp() {
        model = new StubChatModel();
        toolRegistry = new ToolRegistry();
        HookChain chain = new HookChain();
        HookDispatcher dispatcher = new HookDispatcher(chain);

        loop = new ReActLoop(model, new ToolExecutor(toolRegistry),
            dispatcher, toolRegistry);
    }

    // ========================================================================
    // 推理阶段
    // ========================================================================

    @Test
    void testReasoningWithoutTools() {
        model.setResponse(new ChatResponse(
            AssistantMessage.of("Hello, I am an AI"),
            new ChatUsage(10, 5), "stop", null));

        LoopContext ctx = buildContext(List.of(UserMessage.of("Hi")));
        ChatResponse response = loop.reasoning(ctx).block();

        assertNotNull(response);
        assertEquals("Hello, I am an AI", response.getMessage().getTextContent());
    }

    @Test
    void testReasoningWithTools() {
        toolRegistry.register(new EchoTool());
        model.setResponse(new ChatResponse(
            AssistantMessage.of("I will use echo"),
            new ChatUsage(10, 5), "stop", null));

        LoopContext ctx = buildContext(List.of(UserMessage.of("echo hello")));
        ChatResponse response = loop.reasoning(ctx).block();

        assertNotNull(response);
        assertEquals("I will use echo", response.getMessage().getTextContent());
    }

    @Test
    void testReasoningHookAbort() {
        HookChain chain = new HookChain();
        chain.register(new Hook() {
            @Override public String getName() { return "blocker"; }
            @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(HookEventType.PRE_REASONING); }
            @Override public int getPriority() { return 1; }
            @Override
            public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
                return Mono.just(HookResult.abort("reasoning blocked"));
            }
        });

        ReActLoop l = new ReActLoop(model, new ToolExecutor(toolRegistry),
            new HookDispatcher(chain), toolRegistry);

        LoopContext ctx = buildContext(List.of(UserMessage.of("Hi")));
        assertThrows(Exception.class, () -> l.reasoning(ctx).block());
    }

    @Test
    void testReasoningHookInterrupt() {
        HookChain chain = new HookChain();
        chain.register(new Hook() {
            @Override public String getName() { return "pauser"; }
            @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(HookEventType.PRE_REASONING); }
            @Override public int getPriority() { return 1; }
            @Override
            public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
                return Mono.just(HookResult.interrupt("needs review"));
            }
        });

        ReActLoop l = new ReActLoop(model, new ToolExecutor(toolRegistry),
            new HookDispatcher(chain), toolRegistry);

        LoopContext ctx = buildContext(List.of(UserMessage.of("Hi")));
        ChatResponse response = l.reasoning(ctx).block();

        assertNotNull(response);
        assertTrue(response.getMessage().getTextContent().contains("中断"));
        assertTrue(response.getMessage().getTextContent().contains("needs review"));
    }

    // ========================================================================
    // 流式推理
    // ========================================================================

    @Test
    void testReasoningStream() {
        model.setStreamChunks(List.of(
            ChatStreamChunk.builder().delta("Hello").type(ChatStreamChunk.TYPE_TEXT).build(),
            ChatStreamChunk.builder().delta(" streaming").type(ChatStreamChunk.TYPE_TEXT)
                .finishReason("stop").build()));

        LoopContext ctx = buildStreamContext(List.of(UserMessage.of("Hi")));
        List<ChatStreamChunk> chunks = loop.reasoningStream(ctx).collectList().block();

        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        String collected = chunks.stream()
            .filter(c -> c.getDelta() != null)
            .map(ChatStreamChunk::getDelta)
            .reduce("", String::concat);
        assertEquals("Hello streaming", collected);
    }

    // ========================================================================
    // 主循环
    // ========================================================================

    @Test
    void testFullReActLoop() {
        toolRegistry.register(new EchoTool());

        Msg toolCallMsg = AssistantMessage.builder()
            .addToolUse("tc1", "echo", "{\"input\":\"hello\"}")
            .build();
        model.setResponses(List.of(
            new ChatResponse(toolCallMsg, new ChatUsage(5, 10), "tool_calls", null),
            new ChatResponse(AssistantMessage.of("Echo result: ECHO: hello"),
                new ChatUsage(5, 5), "stop", null)));

        LoopContext ctx = buildContext(List.of(UserMessage.of("echo hello please")));
        ChatResponse response = loop.execute(ctx).block();

        assertNotNull(response);
        assertTrue(response.getMessage().getTextContent().contains("ECHO"));
    }

    @Test
    void testParallelToolExecution() {
        toolRegistry.register(new EchoTool());
        toolRegistry.register(new ReverseTool());

        Msg toolCallMsg = AssistantMessage.builder()
            .addToolUse("tc1", "echo", "{\"input\":\"hello\"}")
            .addToolUse("tc2", "reverse", "{\"input\":\"world\"}")
            .build();
        model.setResponses(List.of(
            new ChatResponse(toolCallMsg, new ChatUsage(10, 20), "tool_calls", null),
            new ChatResponse(AssistantMessage.of("完成"), new ChatUsage(5, 5), "stop", null)));

        LoopContext ctx = buildContext(List.of(UserMessage.of("echo hello and reverse world")));
        ChatResponse response = loop.execute(ctx).block();

        assertNotNull(response);
        assertEquals("完成", response.getMessage().getTextContent());

        // 验证 TOOL 消息按 callId 正确匹配（而非按下标）
        List<Msg> messages = ctx.getMessages();
        List<Msg> toolMsgs = messages.stream()
            .filter(m -> m.getRole() == MsgRole.TOOL).toList();
        assertEquals(2, toolMsgs.size());
        // 每个 TOOL 消息的 tool_use_id 应对应正确的结果内容
        List<String> toolContents = toolMsgs.stream()
            .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
            .map(ToolResultBlock::getContent)
            .toList();
        boolean hasEcho = toolContents.stream().anyMatch(c -> c.contains("ECHO"));
        boolean hasReverse = toolContents.stream().anyMatch(c -> c.contains("DLROW"));
        assertTrue(hasEcho, "应包含 echo 结果，实际: " + toolContents);
        assertTrue(hasReverse, "应包含 reverse 结果，实际: " + toolContents);
    }

    @Test
    void testToolResultCarriesCallId() {
        toolRegistry.register(new EchoTool());

        List<ToolUseBlock> toolCalls = List.of(
            new ToolUseBlock("tc_alpha", "echo", "{\"input\":\"a\"}"),
            new ToolUseBlock("tc_beta", "echo", "{\"input\":\"b\"}"));

        LoopContext ctx = buildContext(List.of(UserMessage.of("test")));
        List<ToolResult> results = loop.acting(ctx, toolCalls).block();

        assertNotNull(results);
        assertEquals(2, results.size());
        for (ToolResult r : results) {
            assertNotNull(r.getCallId(), "每个结果应有 callId");
            assertTrue(r.getCallId().equals("tc_alpha") || r.getCallId().equals("tc_beta"),
                "callId 应为 tc_alpha 或 tc_beta，实际为: " + r.getCallId());
        }
    }

    // ========================================================================
    // 审批中断链路：ToolSuspendException → 循环暂停 → 外部审批 → 恢复
    // ========================================================================

    @Test
    void testApprovalFlowPauseAndResume() {
        ApprovableTool approvableTool = new ApprovableTool(true);
        toolRegistry.register(approvableTool);

        // 模拟 LLM 三轮响应：
        // 第1轮：调用需审批工具 → ToolSuspendException → 循环暂停
        // 第2轮：审批后 LLM 重试工具 → 成功
        // 第3轮：LLM 确认完成
        model.setResponses(List.of(
            new ChatResponse(
                AssistantMessage.builder().addToolUse("tc1", "approvable", "{\"input\":\"transfer\"}").build(),
                new ChatUsage(5, 10), "tool_calls", null),
            new ChatResponse(
                AssistantMessage.builder().addToolUse("tc2", "approvable", "{\"input\":\"transfer\"}").build(),
                new ChatUsage(5, 10), "tool_calls", null),
            new ChatResponse(AssistantMessage.of("操作已批准并执行成功"),
                new ChatUsage(5, 5), "stop", null)));

        LoopContext ctx = buildContext(List.of(UserMessage.of("执行需要审批的操作")));

        // ====== 阶段 1：触发审批中断 ======
        ChatResponse response1 = loop.execute(ctx).block();
        assertNotNull(response1);
        assertTrue(ctx.isInterrupted(), "循环应处于中断状态");

        List<Msg> toolMsgs = ctx.getMessages().stream()
            .filter(m -> m.getRole() == MsgRole.TOOL).toList();
        assertEquals(1, toolMsgs.size());
        List<String> toolContents1 = toolMsgs.stream()
            .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
            .map(ToolResultBlock::getContent).toList();
        assertTrue(toolContents1.get(0).contains("等待审批"),
            "应包含等待审批消息，实际: " + toolContents1);

        // ====== 阶段 2：外部注入审批，恢复执行 ======
        approvableTool.setRequiresApproval(false);
        ctx.interrupt(UserMessage.of("批准执行"));

        ChatResponse response2 = loop.execute(ctx).block();
        assertNotNull(response2);
        assertEquals("操作已批准并执行成功", response2.getMessage().getTextContent());

        // ====== 验证完整消息链路 ======
        // user → assistant(tool_use) → tool(等待审批) →
        // user(批准) → assistant(tool_use) → tool(成功) → assistant(完成)
        List<Msg> allMsgs = ctx.getMessages();
        List<String> allToolResults = allMsgs.stream()
            .filter(m -> m.getRole() == MsgRole.TOOL)
            .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
            .map(ToolResultBlock::getContent)
            .collect(java.util.stream.Collectors.toList());
        assertEquals(2, allToolResults.size(), "应有两次工具结果: " + allToolResults);
        assertTrue(allToolResults.get(0).contains("等待审批"), "第一个应为等待审批");
        assertTrue(allToolResults.get(1).contains("APPROVED"), "第二个应为执行成功，实际: " + allToolResults.get(1));

        boolean hasApprovalMsg = allMsgs.stream()
            .filter(m -> m.getRole() == MsgRole.USER)
            .anyMatch(m -> m.getTextContent().contains("批准执行"));
        assertTrue(hasApprovalMsg, "消息历史中应有用户批准消息");
    }

    @Test
    void testApprovalFlowAbortByHook() {
        ApprovableTool approvableTool = new ApprovableTool(true);
        toolRegistry.register(approvableTool);

        // Hook 在 ON_INTERRUPT 中否决操作
        HookChain chain = new HookChain();
        chain.register(new Hook() {
            @Override public String getName() { return "deny-hook"; }
            @Override public Set<HookEventType> getSubscribedEventTypes() {
                return Set.of(HookEventType.ON_INTERRUPT);
            }
            @Override public int getPriority() { return 1; }
            @Override
            public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
                if (event instanceof InterruptEvent ie && "approvable".equals(ie.getToolName())) {
                    return Mono.just(HookResult.abort("管理员禁止此操作"));
                }
                return Mono.just(HookResult.continue_());
            }
        });

        ReActLoop l = new ReActLoop(model, new ToolExecutor(toolRegistry),
            new HookDispatcher(chain), toolRegistry);

        model.setResponses(List.of(
            new ChatResponse(
                AssistantMessage.builder().addToolUse("tc1", "approvable", "{\"input\":\"transfer\"}").build(),
                new ChatUsage(5, 10), "tool_calls", null),
            new ChatResponse(AssistantMessage.of("操作被系统拒绝"),
                new ChatUsage(5, 5), "stop", null)));

        LoopContext ctx = buildContext(List.of(UserMessage.of("执行操作")));
        ChatResponse response = l.execute(ctx).block();

        assertNotNull(response);
        assertFalse(ctx.isInterrupted(), "被 Hook 否决后不应中断循环");
        assertTrue(response.getMessage().getTextContent().contains("系统拒绝"));

        List<Msg> toolMsgs = ctx.getMessages().stream()
            .filter(m -> m.getRole() == MsgRole.TOOL).toList();
        assertEquals(1, toolMsgs.size());
        List<String> contents = toolMsgs.stream()
            .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
            .map(ToolResultBlock::getContent).toList();
        assertTrue(contents.get(0).contains("操作被拒绝"));
    }

    @Test
    void testApprovalFlowWithoutFeedbackStaysInterrupted() {
        ApprovableTool approvableTool = new ApprovableTool(true);
        toolRegistry.register(approvableTool);

        model.setResponses(List.of(
            new ChatResponse(
                AssistantMessage.builder().addToolUse("tc1", "approvable", "{\"input\":\"transfer\"}").build(),
                new ChatUsage(5, 10), "tool_calls", null)));

        LoopContext ctx = buildContext(List.of(UserMessage.of("操作")));

        loop.execute(ctx).block();
        assertTrue(ctx.isInterrupted(), "应处于中断状态");

        // 不注入反馈再次执行 → 仍返回中断响应，状态不变
        ChatResponse response = loop.execute(ctx).block();
        assertNotNull(response);
        assertTrue(ctx.isInterrupted(), "无反馈时仍应处于中断状态");
    }

    // ========================================================================
    // ApprovalStore 集成：审批创建 → 批准 → ToolExecutor 放行 → consume 清除
    // ========================================================================

    @Test
    void testApprovalStoreFullFlow() {
        InMemoryApprovalStore approvalStore = new InMemoryApprovalStore();
        ApprovableTool approvableTool = new ApprovableTool(true);
        toolRegistry.register(approvableTool);

        // 注册 ApprovalHook + 注入 ToolExecutor
        HookChain chain = new HookChain();
        chain.register(new ApprovalHook(approvalStore));
        ToolExecutor executor = new ToolExecutor(toolRegistry);
        executor.setApprovalStore(approvalStore);
        ReActLoop l = new ReActLoop(model, executor, new HookDispatcher(chain), toolRegistry);

        // LLM: 第1轮 tool_use → ToolSuspendException → 暂停
        //      第2轮 tool_use → ApprovalStore已批准 → 执行成功
        //      第3轮 文本回复
        model.setResponses(List.of(
            new ChatResponse(
                AssistantMessage.builder().addToolUse("tc1", "approvable", "{\"input\":\"txn\"}").build(),
                new ChatUsage(5, 10), "tool_calls", null),
            new ChatResponse(
                AssistantMessage.builder().addToolUse("tc2", "approvable", "{\"input\":\"txn\"}").build(),
                new ChatUsage(5, 10), "tool_calls", null),
            new ChatResponse(AssistantMessage.of("操作已完成"),
                new ChatUsage(5, 5), "stop", null)));

        LoopContext ctx = buildContext(List.of(UserMessage.of("执行操作")));

        // ── 阶段 1: 触发审批 → 暂停 ──
        l.execute(ctx).block();
        assertTrue(ctx.isInterrupted(), "应处于中断状态");

        // 验证 ApprovalHook 已创建 PendingApproval
        List<PendingApproval> pending = approvalStore.getPendingBySession(ctx.getSessionId());
        assertEquals(1, pending.size());
        assertEquals("approvable", pending.get(0).getToolName());
        assertNotNull(pending.get(0).getApprovalId());

        // ── 阶段 2: 审批人批准 ──
        approvalStore.approve(pending.get(0).getApprovalId(), "admin", "同意");
        assertTrue(approvalStore.isApproved(ctx.getSessionId(), "approvable"));

        // 注入反馈 — 保留 requiresApproval=true，靠 ApprovalStore.isApproved 放行
        ctx.interrupt(UserMessage.of("批准执行"));

        // ── 阶段 3: 恢复执行 → ToolExecutor 检查 isApproved → 跳过审批 → 成功 ──
        ChatResponse response = l.execute(ctx).block();
        assertNotNull(response);
        assertEquals("操作已完成", response.getMessage().getTextContent());

        // ── 阶段 4: consume 后批准被清除 ──
        assertFalse(approvalStore.isApproved(ctx.getSessionId(), "approvable"),
            "执行成功后批准记录应被消费");
    }

    @Test
    void testApprovalDenyDoesNotGrantAccess() {
        InMemoryApprovalStore approvalStore = new InMemoryApprovalStore();
        ApprovableTool approvableTool = new ApprovableTool(true);
        toolRegistry.register(approvableTool);

        HookChain chain = new HookChain();
        chain.register(new ApprovalHook(approvalStore));
        ToolExecutor executor = new ToolExecutor(toolRegistry);
        executor.setApprovalStore(approvalStore);
        ReActLoop l = new ReActLoop(model, executor, new HookDispatcher(chain), toolRegistry);

        model.setResponses(List.of(
            new ChatResponse(
                AssistantMessage.builder().addToolUse("tc1", "approvable", "{\"input\":\"txn\"}").build(),
                new ChatUsage(5, 10), "tool_calls", null),
            new ChatResponse(
                AssistantMessage.builder().addToolUse("tc2", "approvable", "{\"input\":\"txn\"}").build(),
                new ChatUsage(5, 10), "tool_calls", null),
            new ChatResponse(AssistantMessage.of("操作已被系统拒绝"),
                new ChatUsage(5, 5), "stop", null)));

        LoopContext ctx = buildContext(List.of(UserMessage.of("操作")));

        // 触发审批
        l.execute(ctx).block();
        List<PendingApproval> pending = approvalStore.getPendingBySession(ctx.getSessionId());
        assertEquals(1, pending.size());

        // 拒绝
        approvalStore.deny(pending.get(0).getApprovalId(), "admin", "风险过高");
        assertFalse(approvalStore.isApproved(ctx.getSessionId(), "approvable"),
            "拒绝后 isApproved 应为 false");

        // 恢复（工具仍需要审批 → 再次触发 ToolSuspendException）
        ctx.interrupt(UserMessage.of("继续"));
        l.execute(ctx).block();

        // 再次触发，应又有一条待审批记录
        List<PendingApproval> pending2 = approvalStore.getPendingBySession(ctx.getSessionId());
        assertTrue(pending2.size() >= 1, "拒绝后再次调用应创建新的审批记录");
    }

    @Test
    void testApprovalOneTimeConsumption() {
        InMemoryApprovalStore approvalStore = new InMemoryApprovalStore();
        ApprovableTool approvableTool = new ApprovableTool(true);
        toolRegistry.register(approvableTool);

        HookChain chain = new HookChain();
        chain.register(new ApprovalHook(approvalStore));
        ToolExecutor executor = new ToolExecutor(toolRegistry);
        executor.setApprovalStore(approvalStore);
        ReActLoop l = new ReActLoop(model, executor, new HookDispatcher(chain), toolRegistry);

        // 第一次批准后执行成功，approval 被 consume
        // 第二次调用时无批准记录 → 再次触发审批
        model.setResponses(List.of(
            // 第一次: 触发审批
            new ChatResponse(
                AssistantMessage.builder().addToolUse("t1", "approvable", "{}").build(),
                new ChatUsage(1, 1), "tool_calls", null),
            // 第一次恢复: 已批准，执行成功
            new ChatResponse(
                AssistantMessage.builder().addToolUse("t2", "approvable", "{}").build(),
                new ChatUsage(1, 1), "tool_calls", null),
            new ChatResponse(AssistantMessage.of("第一次成功"), new ChatUsage(1, 1), "stop", null),
            // 第二次调用: 无批准记录，再次触发审批
            new ChatResponse(
                AssistantMessage.builder().addToolUse("t3", "approvable", "{}").build(),
                new ChatUsage(1, 1), "tool_calls", null),
            new ChatResponse(AssistantMessage.of("第二次也成功"), new ChatUsage(1, 1), "stop", null)));

        LoopContext ctx = buildContext(List.of(UserMessage.of("操作")));

        // 第一次触发审批 → 暂停
        l.execute(ctx).block();
        List<PendingApproval> p1 = approvalStore.getPendingBySession(ctx.getSessionId());
        approvalStore.approve(p1.get(0).getApprovalId(), "admin", "ok");

        // 恢复（保留 requiresApproval=true） → ApprovalStore 放行 → 执行成功 → consume
        ctx.interrupt(UserMessage.of("批准"));
        l.execute(ctx).block();
        assertFalse(approvalStore.isApproved(ctx.getSessionId(), "approvable"),
            "consume 后 isApproved 应为 false");

        // 第二次调用 — 无批准记录 → 应再次触发审批
        LoopContext ctx2 = buildContext(List.of(UserMessage.of("再操作一次")));

        l.execute(ctx2).block();
        // 二次触发 → 新审批记录
        List<PendingApproval> p2 = approvalStore.getPendingBySession(ctx2.getSessionId());
        assertTrue(p2.size() >= 1, "consume 后再次调用应创建新审批记录");
    }

    @Test
    void testReActLoopMaxIterations() {
        model.setResponses(List.of(
            new ChatResponse(AssistantMessage.builder().addToolUse("t1", "noop", "{}").build(),
                new ChatUsage(1, 1), "tool_calls", null),
            new ChatResponse(AssistantMessage.builder().addToolUse("t2", "noop", "{}").build(),
                new ChatUsage(1, 1), "tool_calls", null),
            new ChatResponse(AssistantMessage.of("done"), new ChatUsage(1, 1), "stop", null)));

        LoopContext ctx = LoopContext.builder()
            .agentName("test").sessionId("1").messages(List.of(UserMessage.of("loop")))
            .generateOptions(GenerateOptions.builder().maxTokens(100).build())
            .maxIterations(1).stream(false).build();

        ChatResponse response = loop.execute(ctx).block();
        assertNotNull(response);
    }

    @Test
    void testExecuteWithInterruptedContext() {
        LoopContext ctx = buildContext(List.of(UserMessage.of("Hi")));
        ctx.interrupt();

        ChatResponse response = loop.execute(ctx).block();
        assertNotNull(response);
        assertTrue(response.getMessage().getTextContent().contains("中断"));
    }

    @Test
    void testInterruptWithFeedbackContinuesLoop() {
        // 第一轮：LLM 返回 tool_use
        Msg toolCallMsg = AssistantMessage.builder()
            .addToolUse("tc1", "echo", "{\"input\":\"hello\"}")
            .build();
        // 第二轮：feedback 后 LLM 正常回复
        model.setResponses(List.of(
            new ChatResponse(toolCallMsg, new ChatUsage(5, 10), "tool_calls", null),
            new ChatResponse(AssistantMessage.of("已处理你的反馈"),
                new ChatUsage(5, 5), "stop", null)));

        LoopContext ctx = buildContext(List.of(UserMessage.of("Hi")));
        // 模拟中途注入 feedback
        ctx.interrupt(UserMessage.of("等一下，换个方式回答"));

        toolRegistry.register(new EchoTool());
        ChatResponse response = loop.execute(ctx).block();

        assertNotNull(response);
        assertEquals("已处理你的反馈", response.getMessage().getTextContent());
    }

    @Test
    void testInterruptWithoutFeedbackStops() {
        model.setResponse(new ChatResponse(
            AssistantMessage.of("之前的回复"),
            new ChatUsage(5, 5), "stop", null));

        LoopContext ctx = buildContext(List.of(UserMessage.of("Hi")));
        ctx.setLastResponse(new ChatResponse(
            AssistantMessage.of("之前的回复"), null, null, null));
        ctx.interrupt(); // 无 feedback

        ChatResponse response = loop.execute(ctx).block();
        assertNotNull(response);
        assertEquals("之前的回复", response.getMessage().getTextContent());
    }

    @Test
    void testFullReActLoopStream() {
        model.setStreamChunks(List.of(
            ChatStreamChunk.builder().delta("answer without tools")
                .type(ChatStreamChunk.TYPE_TEXT).finishReason("stop").build()));

        LoopContext ctx = buildStreamContext(List.of(UserMessage.of("question")));
        List<ChatStreamChunk> chunks = loop.executeStream(ctx).collectList().block();

        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
    }

    // ========================================================================
    // 错误处理
    // ========================================================================

    @Test
    void testHandleError() {
        LoopContext ctx = buildContext(List.of());
        loop.handleError(ctx, new RuntimeException("test error")).block();
        assertNotNull(ctx);
    }

    @Test
    void testBuildInterruptedResponse() {
        ChatResponse response = loop.buildInterrupted("manual stop");
        assertNotNull(response);
        assertTrue(response.getMessage().getTextContent().contains("中断"));
        assertTrue(response.getMessage().getTextContent().contains("manual stop"));
    }

    // ========================================================================
    // Hook 上下文构建
    // ========================================================================

    @Test
    void testHookContextBuilding() {
        LoopContext ctx = buildContext(List.of());
        HookContext hc = loop.buildHookContext(ctx);

        assertEquals("test-agent", hc.getAgentName());
        assertEquals("t1", hc.getTenantId());
        assertEquals("u1", hc.getUserId());
        assertEquals(0, hc.getCurrentIteration());
    }

    // ========================================================================
    // 循环上下文
    // ========================================================================

    @Test
    void testLoopContextMessagesAccumulate() {
        LoopContext ctx = buildContext(List.of(UserMessage.of("msg1")));
        ctx.addMessage(AssistantMessage.of("reply1"));
        ctx.addMessage(UserMessage.of("msg2"));

        assertEquals(3, ctx.getMessages().size());
    }

    @Test
    void testLoopContextIterationTracking() {
        LoopContext ctx = LoopContext.builder()
            .agentName("test").messages(List.of()).generateOptions(GenerateOptions.defaults())
            .maxIterations(3).stream(false).build();

        assertEquals(3, ctx.getMaxIterations());
        assertEquals(0, ctx.getIteration());
        ctx.setIteration(2);
        assertEquals(2, ctx.getIteration());
    }

    @Test
    void testLoopContextTokensAccumulate() {
        LoopContext ctx = buildContext(List.of());
        ctx.addTokens(100);
        ctx.addTokens(50);
        assertEquals(150, ctx.getTotalTokens());
    }

    @Test
    void testLoopContextLastResponse() {
        LoopContext ctx = buildContext(List.of());
        ChatResponse resp = new ChatResponse(
            AssistantMessage.of("hi"), null, null, null);
        ctx.setLastResponse(resp);
        assertSame(resp, ctx.getLastResponse());
    }

    @Test
    void testLoopContextInterruptWithFeedback() {
        LoopContext ctx = buildContext(List.of());
        Msg feedback = UserMessage.of("stop this");
        ctx.interrupt(feedback);
        assertTrue(ctx.isInterrupted());
        assertSame(feedback, ctx.getFeedbackMsg());
    }

    // ========================================================================
    // 流式 chunk 重组
    // ========================================================================

    @Test
    void testBuildResponseFromChunks() {
        List<ChatStreamChunk> chunks = List.of(
            ChatStreamChunk.builder().delta("Hello").type(ChatStreamChunk.TYPE_TEXT).build(),
            ChatStreamChunk.builder().delta(" World").type(ChatStreamChunk.TYPE_TEXT).build(),
            ChatStreamChunk.builder().delta(null).finishReason("stop").build());

        ChatResponse resp = loop.buildResponseFromChunks(chunks);
        assertNotNull(resp);
        assertEquals("Hello World", resp.getMessage().getTextContent());
        assertEquals("stop", resp.getFinishReason());
    }

    @Test
    void testBuildResponseFromChunksNull() {
        assertNull(loop.buildResponseFromChunks(null));
        assertNull(loop.buildResponseFromChunks(List.of()));
    }

    @Test
    void testBuildResponseFromChunksWithToolUse() {
        List<ChatStreamChunk> chunks = List.of(
            ChatStreamChunk.builder().toolUseId("tc1").toolName("calc")
                .type(ChatStreamChunk.TYPE_TOOL_USE_START).build(),
            ChatStreamChunk.builder().toolUseId("tc1").delta("{\"expr\":\"1+1\"}")
                .type(ChatStreamChunk.TYPE_TOOL_USE_DELTA).build(),
            ChatStreamChunk.builder().finishReason("tool_calls").build());

        ChatResponse resp = loop.buildResponseFromChunks(chunks);
        assertNotNull(resp);
        assertFalse(resp.getMessage().getToolUseBlocks().isEmpty());
        assertEquals("calc", resp.getMessage().getToolUseBlocks().get(0).getName());
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private LoopContext buildContext(List<Msg> messages) {
        return LoopContext.builder()
            .agentName("test-agent").tenantId("t1").userId("u1").sessionId("1")
            .messages(messages).generateOptions(GenerateOptions.defaults())
            .maxIterations(10).stream(false).build();
    }

    private LoopContext buildStreamContext(List<Msg> messages) {
        return LoopContext.builder()
            .agentName("test-agent").tenantId("t1").userId("u1").sessionId("1")
            .messages(messages).generateOptions(GenerateOptions.defaults())
            .maxIterations(10).stream(true).build();
    }

    // ========================================================================
    // Test Tool
    // ========================================================================

    static class EchoTool implements Tool {
        @Override public String getName() { return "echo"; }
        @Override public String getDescription() { return "echoes input"; }

        @Override
        public ToolSchema getParameters() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("input", Map.of("type", "string"));
            schema.put("properties", props);
            return new ToolSchema("echo", "echoes input", schema);
        }

        @Override
        public Mono<ToolResult> execute(ToolCallContext params) {
            return Mono.just(ToolResult.success("ECHO: " + params.getString("input")));
        }
    }

    static class ApprovableTool implements Tool {
        private volatile boolean requiresApproval;

        ApprovableTool(boolean requiresApproval) { this.requiresApproval = requiresApproval; }
        void setRequiresApproval(boolean v) { this.requiresApproval = v; }

        @Override public String getName() { return "approvable"; }
        @Override public String getDescription() { return "需要审批的工具"; }

        @Override
        public ToolSchema getParameters() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("input", Map.of("type", "string"));
            schema.put("properties", props);
            return new ToolSchema("approvable", "需要审批的工具", schema);
        }

        @Override
        public boolean requiresApproval() { return requiresApproval; }

        @Override
        public Mono<ToolResult> execute(ToolCallContext params) {
            return Mono.just(ToolResult.success("APPROVED: " + params.getString("input")));
        }
    }

    static class ReverseTool implements Tool {
        @Override public String getName() { return "reverse"; }
        @Override public String getDescription() { return "reverses input"; }

        @Override
        public ToolSchema getParameters() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("input", Map.of("type", "string"));
            schema.put("properties", props);
            return new ToolSchema("reverse", "reverses input", schema);
        }

        @Override
        public Mono<ToolResult> execute(ToolCallContext params) {
            String input = params.getString("input");
            String reversed = new StringBuilder(input).reverse().toString().toUpperCase();
            return Mono.just(ToolResult.success(reversed));
        }
    }

    // ========================================================================
    // Stub Model
    // ========================================================================

    static class StubChatModel extends ChatModelBase {
        private ChatResponse response;
        private List<ChatResponse> responses;
        private List<ChatStreamChunk> streamChunks;
        private int callCount;

        StubChatModel() {
            super("test", "stub", msgs -> List.of(Map.of("role", "user", "content", "test")));
        }

        void setResponse(ChatResponse r) { this.response = r; }
        void setResponses(List<ChatResponse> rs) { this.responses = rs; this.callCount = 0; }
        void setStreamChunks(List<ChatStreamChunk> chunks) { this.streamChunks = chunks; }

        @Override protected Map<String, String> buildAuthHeaders() { return Map.of(); }
        @Override protected String buildApiUrl() { return "http://localhost/stub"; }

        @Override
        protected Mono<ChatResponse> doChat(List<Map<String, Object>> messages,
                                            List<ToolSchema> toolSchemas,
                                            GenerateOptions options) {
            if (responses != null && callCount < responses.size()) {
                return Mono.just(responses.get(callCount++));
            }
            return Mono.justOrEmpty(response);
        }

        @Override
        protected Flux<ChatStreamChunk> doStream(List<Map<String, Object>> messages,
                                                  List<ToolSchema> toolSchemas,
                                                  GenerateOptions options) {
            if (streamChunks != null) return Flux.fromIterable(streamChunks);
            return Flux.empty();
        }
    }
}
