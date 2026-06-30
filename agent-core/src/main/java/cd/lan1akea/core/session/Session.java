package cd.lan1akea.core.session;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话实体。
 * 多轮对话的容器，按租户隔离。包含对话历史和元信息。
 */
public class Session {

    /**
     * 唯一会话标识
     */
    private final SessionId id;
    /**
     * 租户 ID（多租户隔离）
     */
    private final String tenantId;
    /**
     * 关联的 Agent 名称
     */
    private final String agentName;
    /**
     * 当前会话状态
     */
    private SessionState state;
    /**
     * 对话轮次列表
     */
    private final List<ChatTurn> turns;
    /**
     * 创建时间戳
     */
    private final LocalDateTime createdAt;
    /**
     * 最后更新时间戳
     */
    private LocalDateTime updatedAt;

    /**
     * 创建会话。
     *
     * @param id        会话标识
     * @param tenantId  租户 ID
     * @param agentName Agent 名称
     * @param state     初始状态（null 默认 ACTIVE）
     * @param turns     对话轮次列表
     * @param createdAt 创建时间戳（null 默认当前时间）
     * @param updatedAt 更新时间戳（null 默认当前时间）
     */
    public Session(SessionId id, String tenantId, String agentName,
                    SessionState state, List<ChatTurn> turns,
                    LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.agentName = agentName;
        this.state = state != null ? state : SessionState.ACTIVE;
        this.turns = turns != null ? new ArrayList<>(turns) : new ArrayList<>();
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
    }

    /**
     * 添加对话轮次并更新时间戳。
     *
     * @param turn 待添加的对话轮次
     */
    public void addTurn(ChatTurn turn) {
        turns.add(turn);
        updatedAt = LocalDateTime.now();
    }

    /**
     * 暂停会话。
     */
    public void pause() {
        this.state = SessionState.PAUSED;
        updatedAt = LocalDateTime.now();
    }

    /**
     * 恢复会话。
     */
    public void resume() {
        this.state = SessionState.ACTIVE;
        updatedAt = LocalDateTime.now();
    }

    /**
     * 关闭会话。
     */
    public void close() {
        this.state = SessionState.CLOSED;
        updatedAt = LocalDateTime.now();
    }

    /**
     * @return 会话 ID
     */
    public SessionId getId() { return id; }
    /**
     * @return 租户 ID
     */
    public String getTenantId() { return tenantId; }
    /**
     * @return Agent 名称
     */
    public String getAgentName() { return agentName; }
    /**
     * @return 当前会话状态
     */
    public SessionState getState() { return state; }
    /**
     * @return 不可修改的对话轮次视图
     */
    public List<ChatTurn> getTurns() { return Collections.unmodifiableList(turns); }
    /**
     * @return 创建时间戳
     */
    public LocalDateTime getCreatedAt() { return createdAt; }
    /**
     * @return 最后更新时间戳
     */
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    /**
     * @return 对话轮次数
     */
    public int getTurnCount() { return turns.size(); }
}
