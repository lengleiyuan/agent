package cd.lan1akea.core.model;

/**
 * 流式响应块。
 * 流式调用中 LLM 逐步产出的单个文本增量或工具调用片段。
 */
public class ChatStreamChunk {

    /**
     * 文本增量（当类型为 text 时有效）
     */
    private final String delta;

    /**
     * 内容类型：text、thinking、tool_use_start、tool_use_delta、tool_use_end
     */
    private final String type;

    /**
     * 关联的工具调用ID（当类型为 tool_use_* 时有效）
     */
    private final String toolUseId;

    /**
     * 关联的工具名称（当类型为 tool_use_start 时有效）
     */
    private final String toolName;

    /**
     * 完成原因（仅最终块有效）
     */
    private final String finishReason;

    /**
     * 流式块索引
     */
    private final int index;

    /**
     * 模型 API 返回的 token 用量（仅最后一个 chunk 有效）
     */
    private final ChatUsage usage;

    /**
     * 通过建造者创建流式响应块。
     *
     * @param builder 已配置字段的建造者
     */
    private ChatStreamChunk(Builder builder) {
        this.delta = builder.delta;
        this.type = builder.type;
        this.toolUseId = builder.toolUseId;
        this.toolName = builder.toolName;
        this.finishReason = builder.finishReason;
        this.index = builder.index;
        this.usage = builder.usage;
    }

    /**
     * @return 文本增量
     */
    public String getDelta() { return delta; }

    /**
     * @return 内容类型
     */
    public String getType() { return type; }

    /**
     * @return 工具调用ID
     */
    public String getToolUseId() { return toolUseId; }

    /**
     * @return 工具名称
     */
    public String getToolName() { return toolName; }

    /**
     * @return 完成原因
     */
    public String getFinishReason() { return finishReason; }

    /**
     * @return 索引
     */
    public int getIndex() { return index; }

    /**
     * @return 模型 API 返回的 token 用量（可能为 null）
     */
    public ChatUsage getUsage() { return usage; }

    /**
     * 纯文本增量块类型常量
     */
    public static final String TYPE_TEXT = "text";
    /**
     * 思考/推理内容块类型常量
     */
    public static final String TYPE_THINKING = "thinking";
    /**
     * 工具调用开始块类型常量
     */
    public static final String TYPE_TOOL_USE_START = "tool_use_start";
    /**
     * 工具调用增量块类型常量
     */
    public static final String TYPE_TOOL_USE_DELTA = "tool_use_delta";
    /**
     * 工具调用结束块类型常量
     */
    public static final String TYPE_TOOL_USE_END = "tool_use_end";
    /**
     * 工具执行错误块类型常量
     */
    public static final String TYPE_TOOL_ERROR = "tool_error";

    public static Builder builder() { return new Builder(); }

    /**
     * 从文本内容和完成原因创建纯文本 chunk。
     *
     * @param text         文本内容
     * @param finishReason 完成原因
     * @return 纯文本类型 chunk
     */
    public static ChatStreamChunk of(String text, String finishReason) {
        return new Builder().delta(text).finishReason(finishReason).build();
    }

    public static class Builder {
        private String delta;
        private String type = TYPE_TEXT;
        private String toolUseId;
        private String toolName;
        private String finishReason;
        private int index;
        private ChatUsage usage;

        public Builder delta(String delta) { this.delta = delta; return this; }
        public Builder type(String type) { this.type = type; return this; }
        public Builder toolUseId(String toolUseId) { this.toolUseId = toolUseId; return this; }
        public Builder toolName(String toolName) { this.toolName = toolName; return this; }
        public Builder finishReason(String finishReason) { this.finishReason = finishReason; return this; }
        public Builder index(int index) { this.index = index; return this; }
        public Builder usage(ChatUsage usage) { this.usage = usage; return this; }

        public ChatStreamChunk build() { return new ChatStreamChunk(this); }
    }
}
