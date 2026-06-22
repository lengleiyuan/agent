package cd.lan1akea.core.session;

import cd.lan1akea.core.util.JsonUtils;
import cd.lan1akea.core.util.IdGenerator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL 会话存储实现。
 */
public class MysqlSessionStore implements SessionStore {

    private final DataSource dataSource;

    public MysqlSessionStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Mono<Session> create(Session session) {
        return Mono.fromCallable(() -> {
            String sql = "INSERT INTO t_session (id, tenant_id, agent_name, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, Long.parseLong(session.getId().getValue()));
                ps.setLong(2, session.getTenantId());
                ps.setString(3, session.getAgentName());
                ps.setString(4, session.getState().name());
                ps.setTimestamp(5, Timestamp.valueOf(session.getCreatedAt()));
                ps.setTimestamp(6, Timestamp.valueOf(session.getUpdatedAt()));
                ps.executeUpdate();
            }
            return session;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Session> findById(SessionId id) {
        return Mono.fromCallable(() -> {
            String sql = "SELECT id, tenant_id, agent_name, status, created_at, updated_at FROM t_session WHERE id = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, Long.parseLong(id.getValue()));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return mapSession(rs);
                    }
                }
            }
            return null;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<Session> listByTenant(long tenantId) {
        return Mono.fromCallable(() -> {
            List<Session> sessions = new ArrayList<>();
            String sql = "SELECT id, tenant_id, agent_name, status, created_at, updated_at FROM t_session WHERE tenant_id = ? ORDER BY updated_at DESC";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        sessions.add(mapSession(rs));
                    }
                }
            }
            return sessions;
        }).subscribeOn(Schedulers.boundedElastic()).flatMapMany(Flux::fromIterable);
    }

    @Override
    public Mono<Void> addTurn(SessionId sessionId, ChatTurn turn) {
        return Mono.fromRunnable(() -> {
            String sql = "INSERT INTO t_chat_turn (id, session_id, turn_order, user_msg_json, assistant_msg_json, tool_calls_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, turn.getId());
                ps.setLong(2, Long.parseLong(sessionId.getValue()));
                ps.setInt(3, turn.getTurnOrder());
                ps.setString(4, turn.getUserMsgJson());
                ps.setString(5, turn.getAssistantMsgJson());
                ps.setString(6, turn.getToolCallsJson());
                ps.setTimestamp(7, Timestamp.valueOf(turn.getCreatedAt()));
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("保存对话轮次失败", e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<Void> updateState(SessionId sessionId, SessionState state) {
        return Mono.fromRunnable(() -> {
            String sql = "UPDATE t_session SET status = ?, updated_at = ? WHERE id = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, state.name());
                ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
                ps.setLong(3, Long.parseLong(sessionId.getValue()));
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("更新会话状态失败", e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<Void> close(SessionId sessionId) {
        return updateState(sessionId, SessionState.CLOSED);
    }

    @Override
    public Mono<Void> delete(SessionId sessionId) {
        return Mono.fromRunnable(() -> {
            try (Connection conn = dataSource.getConnection()) {
                // 先删除轮次
                try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM t_chat_turn WHERE session_id = ?")) {
                    ps.setLong(1, Long.parseLong(sessionId.getValue()));
                    ps.executeUpdate();
                }
                // 再删除会话
                try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM t_session WHERE id = ?")) {
                    ps.setLong(1, Long.parseLong(sessionId.getValue()));
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                throw new RuntimeException("删除会话失败", e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private Session mapSession(ResultSet rs) throws SQLException {
        SessionId id = new SessionId(String.valueOf(rs.getLong("id")));
        long tenantId = rs.getLong("tenant_id");
        String agentName = rs.getString("agent_name");
        SessionState state = SessionState.valueOf(rs.getString("status"));
        LocalDateTime createdAt = rs.getTimestamp("created_at").toLocalDateTime();
        LocalDateTime updatedAt = rs.getTimestamp("updated_at").toLocalDateTime();
        return new Session(id, tenantId, agentName, state, new ArrayList<>(),
            createdAt, updatedAt);
    }
}
