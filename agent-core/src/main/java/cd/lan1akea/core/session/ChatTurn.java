package cd.lan1akea.core.session;

import cd.lan1akea.core.message.Msg;

import java.time.LocalDateTime;

/**
 * 对话轮次。
 * <p>
 * 一次请求-响应对，包含用户消息、助手消息、工具调用。
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

    public ChatTurn(long id, long sessionId, int turnOrder,
                     String userMsgJson, String assistantMsgJson,
                     String toolCallsJson, LocalDateTime createdAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.turnOrder = turnOrder;
        this.userMsgJson = userMsgJson;
        this.assistantMsgJson = assistantMsgJson;
        this.toolCallsJson = toolCallsJson;
        this.createdAt = createdAt;
    }

    public long getId() { return id; }
    public long getSessionId() { return sessionId; }
    public int getTurnOrder() { return turnOrder; }
    public String getUserMsgJson() { return userMsgJson; }
    public String getAssistantMsgJson() { return assistantMsgJson; }
    public String getToolCallsJson() { return toolCallsJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
