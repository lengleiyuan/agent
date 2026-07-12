package cd.lan1akea.core.model;

import cd.lan1akea.core.message.ContentBlock;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.TextBlock;
import cd.lan1akea.core.message.ThinkingBlock;
import cd.lan1akea.core.message.ToolResultBlock;
import cd.lan1akea.core.message.ToolUseBlock;
import cd.lan1akea.core.util.JsonUtils;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;

import java.util.List;
import java.util.Map;

/**
 * 基于 cl100k_base 编码的 Token 估算器。
 * 对齐 OpenAI tiktoken，用于 GPT-4 / GPT-4o / GPT-3.5-turbo 等模型。
 *
 * <p>使用 jtokkit 库进行精确的 BPE 编码，误差与 OpenAI 官方计数一致。</p>
 *
 * <p>Token 计数规则（对齐 OpenAI API 计费逻辑）：</p>
 * <ul>
 *   <li>每条消息有 3-4 token 的消息格式开销（role + 分隔符）</li>
 *   <li>name 字段额外 1 token</li>
 *   <li>文本内容原样编码</li>
 *   <li>工具调用序列化为 JSON 后编码</li>
 *   <li>工具结果携带 tool_call_id 和 name 开销</li>
 * </ul>
 */
public class Cl100kTokenEstimator implements TokenEstimator {

    /**
     * cl100k_base 编码器实例（线程安全）
     */
    private final Encoding encoding;

    /**
     * 每条消息的格式开销 token 数（role + 边界标记）
     */
    static final int MSG_OVERHEAD = 4;

    /**
     * name 字段额外开销
     */
    static final int NAME_OVERHEAD = 1;

    /**
     * 创建 cl100k_base 编码的估算器。
     */
    public Cl100kTokenEstimator() {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    @Override
    public int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        return encoding.countTokens(text);
    }

    @Override
    public int estimate(List<Msg> messages) {
        int total = 0;
        for (Msg msg : messages) {
            total += estimate(msg);
        }
        // 每次请求有 3 token 的 priming 开销
        if (!messages.isEmpty()) total += 3;
        return total;
    }

    @Override
    public int estimate(Msg message) {
        int tokens = MSG_OVERHEAD;

        for (ContentBlock block : message.getContentBlocks()) {
            tokens += countBlock(block);
        }

        // role 本身也算 token
        if (message.getRole() != null) {
            tokens += encoding.countTokens(message.getRole().name().toLowerCase());
        }

        return tokens;
    }

    /**
     * 统计单个内容块的 Token 数。
     */
    private int countBlock(ContentBlock block) {
        if (block instanceof TextBlock tb) {
            return encoding.countTokens(tb.getText());
        }
        if (block instanceof ToolUseBlock tb) {
            return countToolUse(tb);
        }
        if (block instanceof ToolResultBlock tr) {
            return countToolResult(tr);
        }
        if (block instanceof ThinkingBlock th) {
            return encoding.countTokens(th.getThinking());
        }
        // ImageBlock 等不计入 cl100k_base token（由模型自行处理）
        return 0;
    }

    /**
     * 工具调用 Token 计数。
     * 格式对齐 OpenAI API：将 tool_call 序列化为 JSON 后编码。
     */
    private int countToolUse(ToolUseBlock block) {
        // function name
        int tokens = encoding.countTokens(block.getName());
        // arguments JSON string
        String args = block.getArguments();
        if (args != null && !args.isEmpty()) {
            tokens += encoding.countTokens(args);
        }
        return tokens;
    }

    /**
     * 工具结果 Token 计数。
     * 格式对齐 OpenAI API：tool_call_id + name + content。
     */
    private int countToolResult(ToolResultBlock block) {
        int tokens = 0;
        String content = block.getContent();
        if (content != null && !content.isEmpty()) {
            tokens += encoding.countTokens(content);
        }
        // tool_use_id 也是 token 的一部分
        if (block.getToolUseId() != null) {
            tokens += encoding.countTokens(block.getToolUseId());
        }
        return tokens;
    }

    /**
     * 估算工具 Schema 的 Token 数（用于上下文预算）。
     * 将完整的 parameters schema JSON 序列化后编码。
     *
     * @param schema 工具 Schema
     * @return Token 数
     */
    public int estimate(ToolSchema schema) {
        int tokens = encoding.countTokens(schema.getName());
        tokens += encoding.countTokens(schema.getDescription());
        Map<String, Object> params = schema.getParametersSchema();
        if (params != null && !params.isEmpty()) {
            tokens += encoding.countTokens(JsonUtils.toCompactJson(params));
        }
        return tokens;
    }

    /**
     * 估算工具 Schema 列表的 Token 总数。
     *
     * @param schemas 工具 Schema 列表
     * @return Token 总数
     */
    public int estimateSchemas(List<ToolSchema> schemas) {
        int total = 0;
        for (ToolSchema s : schemas) {
            total += estimate(s);
        }
        return total;
    }

    /**
     * 直接对文本进行 Token 计数。
     *
     * @param text 要计数的文本
     * @return Token 数
     */
    public int count(String text) {
        return encoding.countTokens(text);
    }
}
