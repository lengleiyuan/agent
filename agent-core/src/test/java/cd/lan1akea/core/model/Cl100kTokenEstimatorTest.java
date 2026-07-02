package cd.lan1akea.core.model;

import cd.lan1akea.core.message.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cl100kTokenEstimator 精确 Token 计数测试。
 * 验证与 OpenAI tiktoken cl100k_base 对齐的编码行为。
 */
class Cl100kTokenEstimatorTest {

    private Cl100kTokenEstimator estimator;

    @BeforeEach
    void setUp() {
        estimator = new Cl100kTokenEstimator();
    }

    // ═══════════════════════════════════════════════════════════════
    // 单条消息计数
    // ═══════════════════════════════════════════════════════════════

    @Test
    void estimateSingleUserMessage() {
        Msg msg = UserMessage.of("Hello world");
        int tokens = estimator.estimate(msg);
        assertTrue(tokens > 0, "Should count tokens for text content");
    }

    @Test
    void estimateEmptyMessage() {
        Msg msg = UserMessage.of("");
        int tokens = estimator.estimate(msg);
        assertTrue(tokens >= 0, "Empty message should have minimal tokens");
    }

    @Test
    void estimateAssistantMessage() {
        Msg msg = AssistantMessage.of("The quick brown fox");
        int tokens = estimator.estimate(msg);
        assertTrue(tokens > 2, "Assistant message should have tokens: " + tokens);
    }

    @Test
    void estimateSystemMessage() {
        Msg msg = SystemMessage.of("You are a helpful assistant");
        int tokens = estimator.estimate(msg);
        assertTrue(tokens > 3, "System message should have tokens: " + tokens);
    }

    // ═══════════════════════════════════════════════════════════════
    // 消息列表计数
    // ═══════════════════════════════════════════════════════════════

    @Test
    void estimateMessageList() {
        List<Msg> messages = List.of(
            SystemMessage.of("You are helpful"),
            UserMessage.of("Hello"),
            AssistantMessage.of("Hi there")
        );
        int total = estimator.estimate(messages);
        int sum = 0;
        for (Msg m : messages) sum += estimator.estimate(m);
        assertTrue(total > sum, "Total should include priming overhead (3 tokens)");
        assertEquals(sum + 3, total);
    }

    @Test
    void estimateEmptyMessageList() {
        List<Msg> messages = List.of();
        assertEquals(0, estimator.estimate(messages));
    }

    // ═══════════════════════════════════════════════════════════════
    // 消息格式开销
    // ═══════════════════════════════════════════════════════════════

    @Test
    void messageHasFormatOverhead() {
        Msg userMsg = UserMessage.of("hi");
        int tokens = estimator.estimate(userMsg);
        assertTrue(tokens > estimator.count("hi"),
            "Message should have role/format overhead beyond raw text");
    }

    @Test
    void roleContributesTokens() {
        Msg userMsg = UserMessage.of("test");
        // user role name "user" (lowercase) should contribute tokens
        int roleTokens = estimator.count("user");
        assertTrue(roleTokens > 0);
    }

    // ═══════════════════════════════════════════════════════════════
    // 工具调用计数
    // ═══════════════════════════════════════════════════════════════

    @Test
    void estimateToolUseBlock() {
        Msg msg = Msg.builder(MsgRole.ASSISTANT)
            .addToolUse("call_1", "calculator", "{\"expression\":\"2+2\"}")
            .build();
        int tokens = estimator.estimate(msg);
        assertTrue(tokens > 0, "Should count tool call tokens: " + tokens);
    }

    @Test
    void estimateToolCallWithoutArguments() {
        Msg msg = Msg.builder(MsgRole.ASSISTANT)
            .addToolUse("call_2", "get_weather", "{}")
            .build();
        int tokens = estimator.estimate(msg);
        assertTrue(tokens > 0);
    }

    // ═══════════════════════════════════════════════════════════════
    // 工具结果计数
    // ═══════════════════════════════════════════════════════════════

    @Test
    void estimateToolResultBlock() {
        Msg msg = Msg.builder(MsgRole.TOOL)
            .addToolResult("call_1", "result: 4", false)
            .build();
        int tokens = estimator.estimate(msg);
        assertTrue(tokens > 0, "Should count tool result tokens: " + tokens);
    }

    @Test
    void estimateToolResultError() {
        Msg msg = Msg.builder(MsgRole.TOOL)
            .addToolResult("call_err", "Error: timeout", true)
            .build();
        int tokens = estimator.estimate(msg);
        assertTrue(tokens > 0);
    }

    // ═══════════════════════════════════════════════════════════════
    // ThinkingBlock 计数
    // ═══════════════════════════════════════════════════════════════

    @Test
    void estimateThinkingBlock() {
        Msg msg = Msg.builder(MsgRole.ASSISTANT)
            .addThinking("Let me think about this step by step...")
            .build();
        int tokens = estimator.estimate(msg);
        assertTrue(tokens > 3, "Should count thinking tokens: " + tokens);
    }

    // ═══════════════════════════════════════════════════════════════
    // 工具 Schema 计数
    // ═══════════════════════════════════════════════════════════════

    @Test
    void estimateToolSchema() {
        Map<String, Object> paramsSchema = new LinkedHashMap<>();
        paramsSchema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> exprProp = new LinkedHashMap<>();
        exprProp.put("type", "string");
        exprProp.put("description", "数学表达式");
        props.put("expression", exprProp);
        paramsSchema.put("properties", props);

        ToolSchema schema = new ToolSchema("calculator", "执行数学计算", paramsSchema);

        int tokens = estimator.estimate(schema);
        assertTrue(tokens > 5, "Schema should have significant token count: " + tokens);
    }

    @Test
    void estimateToolSchemas() {
        Map<String, Object> params1 = new LinkedHashMap<>();
        params1.put("type", "object");
        ToolSchema s1 = new ToolSchema("tool_a", "desc a", params1);

        Map<String, Object> params2 = new LinkedHashMap<>();
        params2.put("type", "object");
        ToolSchema s2 = new ToolSchema("tool_b", "desc b", params2);

        int total = estimator.estimateSchemas(List.of(s1, s2));
        int sum = estimator.estimate(s1) + estimator.estimate(s2);
        assertEquals(sum, total);
    }

    @Test
    void estimateEmptySchemaList() {
        assertEquals(0, estimator.estimateSchemas(List.of()));
    }

    // ═══════════════════════════════════════════════════════════════
    // count(String) 直接文本计数
    // ═══════════════════════════════════════════════════════════════

    @Test
    void countEnglishText() {
        int tokens = estimator.count("Hello world");
        assertEquals(2, tokens, "Two English words = 2 tokens");
    }

    @Test
    void countChineseText() {
        int tokens = estimator.count("你好世界");
        assertTrue(tokens > 0, "Chinese text should produce tokens");
    }

    @Test
    void countEmptyString() {
        assertEquals(0, estimator.count(""));
    }

    @Test
    void countLongText() {
        String text = "The quick brown fox jumps over the lazy dog";
        int tokens = estimator.count(text);
        assertTrue(tokens > 3, "Long text should have tokens: " + tokens);
    }

    // ═══════════════════════════════════════════════════════════════
    // TokenEstimator.defaults() 使用 Cl100k
    // ═══════════════════════════════════════════════════════════════

    @Test
    void defaultsIsCl100k() {
        TokenEstimator defaults = TokenEstimator.defaults();
        assertTrue(defaults instanceof Cl100kTokenEstimator, "defaults() should return Cl100kTokenEstimator");
    }

    @Test
    void cl100kFactoryReturnsCl100k() {
        TokenEstimator cl = TokenEstimator.cl100k();
        assertTrue(cl instanceof Cl100kTokenEstimator);
    }

    // ═══════════════════════════════════════════════════════════════
    // 一致性
    // ═══════════════════════════════════════════════════════════════

    @Test
    void sameTextSameTokens() {
        int t1 = estimator.count("consistency test");
        int t2 = estimator.count("consistency test");
        assertEquals(t1, t2, "Same text should produce same token count");
    }

    @Test
    void longerTextMoreTokens() {
        int short_ = estimator.count("hi");
        int long_ = estimator.count("This is a much longer piece of text that should produce more tokens");
        assertTrue(long_ > short_, "Longer text should have more tokens");
    }
}
