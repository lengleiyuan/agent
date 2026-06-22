package cd.lan1akea.core.state;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.sql.*;

/**
 * MySQL 状态存储实现。
 */
public class MysqlStateStore implements StateStore {

    private final DataSource dataSource;

    public MysqlStateStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Mono<Void> save(AgentState state) {
        return Mono.fromRunnable(() -> {
            String sql = "INSERT INTO t_agent_state (id, tenant_id, session_id, agent_name, state_json, created_at) VALUES (?, ?, ?, ?, ?, ?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, cd.lan1akea.core.util.IdGenerator.nextId());
                ps.setLong(2, 0L); // tenant_id from context
                ps.setLong(3, Long.parseLong(state.getSessionId()));
                ps.setString(4, state.getAgentName());
                ps.setString(5, cd.lan1akea.core.util.JsonUtils.toCompactJson(state));
                ps.setTimestamp(6, new Timestamp(state.getTimestamp()));
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("保存Agent状态失败", e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<AgentState> loadLatest(String sessionId) {
        return Mono.fromCallable(() -> {
            String sql = "SELECT state_json FROM t_agent_state WHERE session_id = ? ORDER BY created_at DESC LIMIT 1";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, Long.parseLong(sessionId));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String json = rs.getString("state_json");
                        return cd.lan1akea.core.util.JsonUtils.fromJson(json, AgentState.class);
                    }
                }
            }
            return null;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> deleteBySession(String sessionId) {
        return Mono.fromRunnable(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM t_agent_state WHERE session_id = ?")) {
                ps.setLong(1, Long.parseLong(sessionId));
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("删除Agent状态失败", e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }
}
