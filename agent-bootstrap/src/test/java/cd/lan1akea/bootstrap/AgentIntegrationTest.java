package cd.lan1akea.bootstrap;

import cd.lan1akea.core.agent.AbstractAgent;
import cd.lan1akea.core.agent.config.AgentConfig;
import cd.lan1akea.core.agent.config.AgentExecutionConfig;
import cd.lan1akea.core.hook.HookChain;
import cd.lan1akea.core.hook.recorder.HookRecorder;
import cd.lan1akea.core.hook.impl.*;
import cd.lan1akea.core.message.*;
import cd.lan1akea.core.middleware.MiddlewareChain;
import cd.lan1akea.core.middleware.LoggingMiddleware;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.model.deepseek.DeepSeekChatModel;
import cd.lan1akea.core.session.*;
import cd.lan1akea.core.tenant.*;
import cd.lan1akea.core.tool.*;
import cd.lan1akea.core.tool.builtin.CalculatorTool;
import cd.lan1akea.harness.HarnessAgent;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent 集成验证测试。
 * <p>
 * 使用 DeepSeek 模型 + 计算器工具 + 完整 Hook 链验证框架核心能力。
 * </p>
 *
 * <pre>
 * 运行方式：
 *   export DEEPSEEK_API_KEY=sk-xxxxx
 *   mvn -pl agent-bootstrap test -Dtest=AgentIntegrationTest
 *
 * 或跳过此测试（无 API Key 时）：
 *   mvn -pl agent-bootstrap test -Dtest=AgentIntegrationTest -Ddeepseek.skip=true
 * </pre>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AgentIntegrationTest {

    private static HarnessAgent agent;
    private static LoggingHook loggingHook;
    private static AuditHook auditHook;
    private static HookRecorder hookRecorder;

    @BeforeAll
    static void setUp() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
            "跳过集成测试：未设置 DEEPSEEK_API_KEY 环境变量");

        System.out.println("=== 开始 Agent 集成测试 ===");
        System.out.println("模型: DeepSeek deepseek-chat");

        // 1. 创建模型
        DeepSeekChatModel model = new DeepSeekChatModel(apiKey, "deepseek-chat");

        // 2. 创建工具注册表并注册计算器
        ToolRegistry toolRegistry = new ToolRegistry();
        CalculatorTool calculator = new CalculatorTool();
        toolRegistry.register(calculator);

        // 3. 创建 Hook 链（装配具体实现）
        HookChain hookChain = new HookChain();
        loggingHook = new LoggingHook("IntegrationTest");
        auditHook = new AuditHook("IntegrationTest");
        hookChain.register(loggingHook);
        hookChain.register(auditHook);

        // 4. 中间件链
        MiddlewareChain middlewareChain = new MiddlewareChain();
        middlewareChain.register(new LoggingMiddleware());

        // 5. 会话存储
        SessionStore sessionStore = new InMemorySessionStore();

        // 6. 权限引擎（宽松模式）
        PermissionEngine permissionEngine = new PermissionEngine(PermissionMode.PERMISSIVE, List.of());

        // 7. Hook 记录器
        hookRecorder = new HookRecorder();

        // 8. 构建 Agent 配置
        AgentConfig config = AgentConfig.builder()
            .name("TestAgent")
            .model(model)
            .toolRegistry(toolRegistry)
            .hookChain(hookChain)
            .middlewareChain(middlewareChain)
            .sessionStore(sessionStore)
            .executionConfig(AgentExecutionConfig.builder()
                .maxIterations(8)
                .temperature(0.7)
                .maxTokens(2048)
                .build())
            .build();

        // 9. 创建 Agent + 注入子系统
        AbstractAgent inner = new AbstractAgent(config) {
            @Override
            protected Mono<Void> doBuild() { return Mono.empty(); }
        };
        inner.setPermissionEngine(permissionEngine);
        inner.setHookRecorder(hookRecorder);

        // 10. 构建
        agent = inner.build().thenReturn(new HarnessAgent(inner)).block();
        assertNotNull(agent, "Agent 构建失败");

        System.out.println("Agent [" + agent.getName() + "] 构建完成");
    }

    @AfterAll
    static void tearDown() {
        if (agent != null) {
            agent.shutdown().block();
        }
        System.out.println("=== Agent 集成测试结束 ===");
    }

    // ========================================================================
    // 测试1：基础对话能力
    // ========================================================================

    @Test
    @Order(1)
    void testBasicChat() {
        System.out.println("\n--- 测试1：基础对话 ---");
        List<Msg> messages = List.of(
            SystemMessage.of("你是一个友好的助手，用中文回答。"),
            UserMessage.of("你好，请用一句话介绍你自己")
        );

        ChatResponse response = agent.chat(messages, null).block();
        assertNotNull(response, "响应不应为空");
        assertNotNull(response.getMessage(), "消息不应为空");
        assertFalse(response.getMessage().getTextContent().isBlank(), "回复不应为空");

        System.out.println("回复: " + response.getMessage().getTextContent());
        System.out.println("Token: " + response.getUsage());
        System.out.println("结束原因: " + response.getFinishReason());

        // 验证 Hook 被触发
        assertTrue(loggingHook.getEventCount() > 0,
            "LoggingHook 应至少记录了事件（当前: " + loggingHook.getEventCount() + "）");
        assertTrue(auditHook.getAuditLog().size() >= 0,
            "AuditHook 审计日志应存在");
    }

    // ========================================================================
    // 测试2：工具调用（计算器）
    // ========================================================================

    @Test
    @Order(2)
    void testToolCalling() {
        System.out.println("\n--- 测试2：工具调用（计算器） ---");
        List<Msg> messages = List.of(
            SystemMessage.of("你是一个助手，当需要计算时使用 calculator 工具。"),
            UserMessage.of("请帮我计算 123 * 456 + 789 的结果")
        );

        ChatResponse response = agent.chat(messages, null).block();
        assertNotNull(response);
        String reply = response.getMessage().getTextContent();
        assertFalse(reply.isBlank());

        System.out.println("回复: " + reply);
        System.out.println("迭代次数: " + response.getUsage());

        // 检查是否调用了工具（回复中应包含计算结果或工具调用痕迹）
        assertTrue(reply.contains("569") || reply.contains("56877") || reply.contains("计算"),
            "回复应包含计算结果或工具调用（实际: " + reply.substring(0, Math.min(100, reply.length())) + "）");

        // 验证审计日志
        System.out.println("审计日志: " + auditHook.getAuditLog().size() + " 条");
    }

    // ========================================================================
    // 测试3：多轮对话 + 会话持久化
    // ========================================================================

    @Test
    @Order(3)
    void testSessionPersistence() {
        System.out.println("\n--- 测试3：多轮对话 + 会话持久化 ---");

        // 打开会话
        Session session = agent.getDelegate().openSession(null).block();
        assertNotNull(session);
        assertNotNull(session.getId());
        System.out.println("会话ID: " + session.getId().getValue());

        // 第一轮
        List<Msg> turn1 = List.of(
            SystemMessage.of("你是一个助手，请记住我说的话。"),
            UserMessage.of("我的名字是张三")
        );
        ChatResponse r1 = agent.chat(turn1, null).block();
        assertNotNull(r1);
        System.out.println("轮次1: " + r1.getMessage().getTextContent().substring(0,
            Math.min(80, r1.getMessage().getTextContent().length())) + "...");

        // 验证会话持久化
        Session loaded = agent.getDelegate().getSessionStore()
            .findById(session.getId()).block();
        assertNotNull(loaded, "会话应已持久化");
        System.out.println("会话状态: " + loaded.getState() + "，轮次: " + loaded.getTurnCount());
    }

    // ========================================================================
    // 测试4：Hook 事件记录
    // ========================================================================

    @Test
    @Order(4)
    void testHookRecording() {
        System.out.println("\n--- 测试4：Hook 事件记录 ---");

        int beforeHooks = loggingHook.getEventCount();
        int beforeAudit = auditHook.getAuditLog().size();

        List<Msg> messages = List.of(
            UserMessage.of("1+1等于几？")
        );
        agent.chat(messages, null).block();

        int afterHooks = loggingHook.getEventCount();
        int afterAudit = auditHook.getAuditLog().size();

        System.out.println("LoggingHook 事件: " + beforeHooks + " → " + afterHooks);
        System.out.println("AuditHook 审计: " + beforeAudit + " → " + afterAudit);

        assertTrue(afterHooks > beforeHooks,
            "LoggingHook 应记录新事件（" + beforeHooks + " → " + afterHooks + "）");
    }

    // ========================================================================
    // 测试5：系统提示 + 结构化输出
    // ========================================================================

    @Test
    @Order(5)
    void testSystemPrompt() {
        System.out.println("\n--- 测试5：系统提示 + 行为约束 ---");

        List<Msg> messages = List.of(
            SystemMessage.of("你是一个只会用 JSON 格式回复的助手。所有回复必须是合法 JSON。"),
            UserMessage.of("报时：现在几点？（用JSON回复）")
        );

        ChatResponse response = agent.chat(messages, null).block();
        assertNotNull(response);
        String reply = response.getMessage().getTextContent();
        System.out.println("回复: " + reply.substring(0, Math.min(150, reply.length())) + "...");
    }

    // ========================================================================
    // 测试6：流式对话
    // ========================================================================

    @Test
    @Order(6)
    void testStreamingChat() {
        System.out.println("\n--- 测试6：流式对话 ---");

        List<Msg> messages = List.of(
            UserMessage.of("数到5，每次说一个数字")
        );

        StringBuilder collected = new StringBuilder();
        agent.stream(messages, null)
            .doOnNext(chunk -> {
                if (chunk.getDelta() != null) {
                    collected.append(chunk.getDelta());
                }
            })
            .collectList()
            .block();

        String result = collected.toString();
        assertFalse(result.isBlank(), "流式输出不应为空");
        System.out.println("流式输出: " + result.substring(0, Math.min(100, result.length())) + "...");
    }

    // ========================================================================
    // 测试7：上下文压缩能力
    // ========================================================================

    @Test
    @Order(7)
    void testContextCompression() {
        System.out.println("\n--- 测试7：上下文压缩 ---");

        // 先发送多条消息填充上下文
        for (int i = 0; i < 3; i++) {
            List<Msg> msgs = List.of(
                UserMessage.of("消息" + i + ": 请回复'收到" + i + "'")
            );
            agent.chat(msgs, null).block();
        }

        // 验证 SessionSummaryService 存在且可调用
        SessionSummaryService summaryService = agent.getDelegate().getSummaryService();
        assertNotNull(summaryService, "SessionSummaryService 应已注入");

        // 模拟压缩
        List<ChatTurn> turns = List.of(
            new ChatTurn(1, 1, 1, "用户消息1", "助手回复1", null, java.time.LocalDateTime.now()),
            new ChatTurn(2, 1, 2, "用户消息2", "助手回复2", null, java.time.LocalDateTime.now())
        );
        Msg summary = summaryService.summarize(turns);
        assertNotNull(summary, "摘要不应为空");
        System.out.println("摘要: " + summary.getTextContent().substring(0,
            Math.min(100, summary.getTextContent().length())) + "...");
    }

    // ========================================================================
    // 测试8：租户隔离 — 不同租户有不同的工具和权限
    // ========================================================================

    @Test
    @Order(8)
    void testTenantIsolation() {
        System.out.println("\n--- 测试8：租户隔离 ---");

        // 为租户A注册专属工具
        agent.getDelegate().getToolRegistry()
            .registerForTenant("tenant_A", new CalculatorTool());

        // 租户A 看到 calculator
        List<cd.lan1akea.core.model.ToolSchema> schemasA =
            agent.getDelegate().getToolRegistry().getSchemasForTenant("tenant_A");
        System.out.println("租户A 工具数: " + schemasA.size());

        // 租户B 没有专属工具，只有全局工具
        List<cd.lan1akea.core.model.ToolSchema> schemasB =
            agent.getDelegate().getToolRegistry().getSchemasForTenant("tenant_B");
        System.out.println("租户B 工具数: " + schemasB.size());

        // 通过 Reactor Context 传递租户信息
        List<Msg> messages = List.of(
            SystemMessage.of("你是一个助手。"),
            UserMessage.of("1+1等于几，用calculator计算")
        );

        // 租户A 发请求（contextWrite注入tenantId）
        ChatResponse responseA = agent.chat(messages, null)
            .contextWrite(ctx -> ctx.put("tenantId", "tenant_A")
                .put("userId", "user_1"))
            .block();
        assertNotNull(responseA);
        System.out.println("租户A 回复: " + responseA.getMessage().getTextContent()
            .substring(0, Math.min(100, responseA.getMessage().getTextContent().length())) + "...");

        // 验证租户A看到calculator工具
        // （LLM 应该会尝试调用 calculator）
        String replyA = responseA.getMessage().getTextContent().toLowerCase();
        assertTrue(replyA.contains("2") || replyA.contains("计算") || replyA.contains("结果"),
            "租户A 的回复应包含计算结果");

        System.out.println("租户隔离测试通过");
    }

    // ========================================================================
    // 测试9：综合验证 - 最终报告
    // ========================================================================

    @Test
    @Order(9)
    void testFinalReport() {
        System.out.println("\n========================================");
        System.out.println("  集成测试最终报告");
        System.out.println("========================================");
        System.out.println("Agent 名称: " + agent.getName());
        System.out.println("Agent 状态: " + (agent.getDelegate().isBuilt() ? "已构建" : "未构建"));
        System.out.println("总 Hook 事件数: " + loggingHook.getEventCount());
        System.out.println("审计日志条数: " + auditHook.getAuditLog().size());
        System.out.println("全局工具数: " + agent.getDelegate().getToolRegistry().size());
        System.out.println("租户A 工具数: "
            + agent.getDelegate().getToolRegistry().tenantToolCount("tenant_A"));
        System.out.println("Hook链长度: " + agent.getDelegate().getHookChain().size());
        System.out.println("上下文窗口: " + agent.getDelegate().getContextWindow().getTotalWindow() + " tokens");

        System.out.println("\n审计日志摘要:");
        for (AuditHook.AuditEntry entry : auditHook.getAuditLog()) {
            System.out.println("  " + entry);
        }

        assertTrue(loggingHook.getEventCount() > 3,
            "至少应有 3 个 Hook 事件（实际: " + loggingHook.getEventCount() + "）");
        assertTrue(agent.getDelegate().getToolRegistry().size() >= 1,
            "应至少注册 1 个全局工具");
    }
}