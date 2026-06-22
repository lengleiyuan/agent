package cd.lan1akea.core.model;

import cd.lan1akea.core.message.Msg;

/**
 * 聊天响应。
 * <p>
 * 封装 LLM 调用返回的消息、Token用量、以及元信息。
 * </p>
 */
public class ChatResponse {

    /** 消息（含文本回复和工具调用） */
    private final Msg message;

    /** Token 用量信息 */
    private final ChatUsage usage;

    /** 完成原因（stop、length、tool_calls 等） */
    private final String finishReason;

    /** 模型名称 */
    private final String modelName;

    public ChatResponse(Msg message, ChatUsage usage, String finishReason, String modelName) {
        this.message = message;
        this.usage = usage;
        this.finishReason = finishReason;
        this.modelName = modelName;
    }

    /** @return 消息 */
    public Msg getMessage() { return message; }

    /** @return Token用量 */
    public ChatUsage getUsage() { return usage; }

    /** @return 完成原因 */
    public String getFinishReason() { return finishReason; }

    /** @return 模型名称 */
    public String getModelName() { return modelName; }
}
