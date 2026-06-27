package cd.lan1akea.core.session;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.util.JsonUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 对话轮次。
 * 一次请求-响应对，包含用户消息、助手消息、工具调用。
 * 结构化 Msg 列表优先；JSON 字符串字段作为序列化缓存/兼容。
 */
public class ChatTurn {

    /**
     * 唯一轮次 ID
     */
    private final long id;
    /**
     * 所属会话 ID
     */
    private final long sessionId;
    /**
     * 会话内轮次顺序
     */
    private final int turnOrder;
    /**
     * 用户消息 JSON（序列化缓存/回退）
     */
    private final String userMsgJson;
    /**
     * 助手消息 JSON（序列化缓存/回退）
     */
    private final String assistantMsgJson;
    /**
     * 工具调用 JSON（序列化缓存/回退）
     */
    private final String toolCallsJson;
    /**
     * 创建时间戳
     */
    private final LocalDateTime createdAt;

    /**
     * 结构化用户消息（含内容块）
     */
    private final List<Msg> userMessages;
    /**
     * 结构化助手消息（含内容块）
     */
    private final List<Msg> assistantMessages;
    /**
     * 结构化工具调用消息
     */
    private final List<Msg> toolMessages;

    /**
     * 创建不含结构化消息列表的对话轮次。
     *
     * @param id               轮次 ID
     * @param sessionId        会话 ID
     * @param turnOrder        轮次序号
     * @param userMsgJson      用户消息 JSON
     * @param assistantMsgJson 助手消息 JSON
     * @param toolCallsJson    工具调用 JSON
     * @param createdAt        创建时间戳
     */
    public ChatTurn(long id, long sessionId, int turnOrder,
                     String userMsgJson, String assistantMsgJson,
                     String toolCallsJson, LocalDateTime createdAt) {
        this(id, sessionId, turnOrder, userMsgJson, assistantMsgJson, toolCallsJson,
            createdAt, null, null, null);
    }

    /**
     * 创建可含结构化消息列表的对话轮次。
     *
     * @param id                轮次 ID
     * @param sessionId         会话 ID
     * @param turnOrder         轮次序号
     * @param userMsgJson       用户消息 JSON
     * @param assistantMsgJson  助手消息 JSON
     * @param toolCallsJson     工具调用 JSON
     * @param createdAt         创建时间戳
     * @param userMessages      结构化用户消息（可为 null）
     * @param assistantMessages 结构化助手消息（可为 null）
     * @param toolMessages      结构化工具消息（可为 null）
     */
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

    /**
     * @return 轮次 ID
     */
    public long getId() { return id; }
    /**
     * @return 会话 ID
     */
    public long getSessionId() { return sessionId; }
    /**
     * @return 轮次序号
     */
    public int getTurnOrder() { return turnOrder; }
    /**
     * @return 用户消息 JSON（回退）
     */
    public String getUserMsgJson() { return userMsgJson; }
    /**
     * @return 助手消息 JSON（回退）
     */
    public String getAssistantMsgJson() { return assistantMsgJson; }
    /**
     * @return 工具调用 JSON（回退）
     */
    public String getToolCallsJson() { return toolCallsJson; }
    /**
     * @return 创建时间戳
     */
    public LocalDateTime getCreatedAt() { return createdAt; }

    /**
     * @return 结构化用户消息（优先），null 时回退到 getUserMsgJson()
     */
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

    /**
     * @return 结构化助手消息（优先），null 时回退到 getAssistantMsgJson()
     */
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

    /**
     * @return 结构化工具消息（优先），null 时回退到 getToolCallsJson()
     */
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
