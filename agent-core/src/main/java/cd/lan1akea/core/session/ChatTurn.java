package cd.lan1akea.core.session;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.util.JsonUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 对话轮次。
 * <p>
 * 一次请求-响应对，包含用户消息、助手消息、工具调用。
 * 结构化 Msg 列表优先；JSON 字符串字段作为序列化缓存/兼容。
 * </p>
 */
public class ChatTurn {

    private final long id;
    private final long sessionId;
    private final int turnOrder;
    private final String userMsgJson;
    private final String assistantMsgJson;
    private final String toolCallsJson;
    private final LocalDateTime createdAt;

    /** 结构化的用户消息列表（含 ContentBlock），序列化/反序列化保留完整结构 */
    private final List<Msg> userMessages;
    /** 结构化的助手消息列表（含 ContentBlock） */
    private final List<Msg> assistantMessages;
    /** 结构化的工具调用消息列表 */
    private final List<Msg> toolMessages;

    public ChatTurn(long id, long sessionId, int turnOrder,
                     String userMsgJson, String assistantMsgJson,
                     String toolCallsJson, LocalDateTime createdAt) {
        this(id, sessionId, turnOrder, userMsgJson, assistantMsgJson, toolCallsJson,
            createdAt, null, null, null);
    }

    public ChatTurn(long id, long sessionId, int turnOrder,
                     String userMsgJson, String assistantMsgJson,
                     String toolCallsJson, LocalDateTime createdAt,
                     List<Msg> userMessages, List<Msg> assistantMessages, List<Msg> toolMessages) {
        this.id = id;
        this.sessionId = sessionId;
        this.turnOrder = turnOrder;
        this.userMsgJson = userMsgJson;
        this.assistantMsgJson = assistantMsgJson;
        this.toolCallsJson = toolCallsJson;
        this.createdAt = createdAt;
        this.userMessages = userMessages != null ? Collections.unmodifiableList(userMessages) : null;
        this.assistantMessages = assistantMessages != null ? Collections.unmodifiableList(assistantMessages) : null;
        this.toolMessages = toolMessages != null ? Collections.unmodifiableList(toolMessages) : null;
    }

    public long getId() { return id; }
    public long getSessionId() { return sessionId; }
    public int getTurnOrder() { return turnOrder; }
    public String getUserMsgJson() { return userMsgJson; }
    public String getAssistantMsgJson() { return assistantMsgJson; }
    public String getToolCallsJson() { return toolCallsJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    /** @return 结构化用户消息（优先），null 时回退到 {@link #getUserMsgJson()} */
    public List<Msg> getUserMessages() {
        if (userMessages != null) return userMessages;
        if (userMsgJson != null && !userMsgJson.isEmpty()) {
            try {
                if (JsonUtils.isValidJson(userMsgJson)) {
                    return List.of(JsonUtils.fromJson(userMsgJson, Msg.class));
                }
            } catch (Exception ignored) {}
            return List.of(cd.lan1akea.core.message.UserMessage.of(userMsgJson));
        }
        return Collections.emptyList();
    }

    /** @return 结构化助手消息（优先），null 时回退到 {@link #getAssistantMsgJson()} */
    public List<Msg> getAssistantMessages() {
        if (assistantMessages != null) return assistantMessages;
        if (assistantMsgJson != null && !assistantMsgJson.isEmpty()) {
            try {
                if (JsonUtils.isValidJson(assistantMsgJson)) {
                    return List.of(JsonUtils.fromJson(assistantMsgJson, Msg.class));
                }
            } catch (Exception ignored) {}
            return List.of(cd.lan1akea.core.message.AssistantMessage.of(assistantMsgJson));
        }
        return Collections.emptyList();
    }

    /** @return 结构化工具消息（优先），null 时回退到 {@link #getToolCallsJson()} */
    public List<Msg> getToolMessages() {
        if (toolMessages != null) return toolMessages;
        if (toolCallsJson != null && !toolCallsJson.isEmpty()) {
            try {
                if (JsonUtils.isValidJson(toolCallsJson)) {
                    return List.of(JsonUtils.fromJson(toolCallsJson, Msg.class));
                }
            } catch (Exception ignored) {}
        }
        return Collections.emptyList();
    }
}
