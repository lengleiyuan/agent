package cd.lan1akea.core.session;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话实体。
 * <p>
 * 多轮对话的容器，按租户隔离。包含对话历史和元信息。
 * </p>
 */
public class Session {

    private final SessionId id;
    private final long tenantId;
    private final String agentName;
    private SessionState state;
    private final List<ChatTurn> turns;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Session(SessionId id, long tenantId, String agentName,
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

    public void addTurn(ChatTurn turn) {
        turns.add(turn);
        updatedAt = LocalDateTime.now();
    }

    public void pause() {
        this.state = SessionState.PAUSED;
        updatedAt = LocalDateTime.now();
    }

    public void resume() {
        this.state = SessionState.ACTIVE;
        updatedAt = LocalDateTime.now();
    }

    public void close() {
        this.state = SessionState.CLOSED;
        updatedAt = LocalDateTime.now();
    }

    public SessionId getId() { return id; }
    public long getTenantId() { return tenantId; }
    public String getAgentName() { return agentName; }
    public SessionState getState() { return state; }
    public List<ChatTurn> getTurns() { return Collections.unmodifiableList(turns); }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public int getTurnCount() { return turns.size(); }
}
