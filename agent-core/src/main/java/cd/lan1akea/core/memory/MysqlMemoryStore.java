package cd.lan1akea.core.memory;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL 记忆存储实现。
 */
public class MysqlMemoryStore implements MemoryStore {

    private final DataSource dataSource;

    public MysqlMemoryStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Mono<Void> save(MemoryEntry entry) {
        return Mono.fromRunnable(() -> {
            String sql = "INSERT INTO t_memory_entry (id, tenant_id, user_id, content, embedding_json, metadata_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, Long.parseLong(entry.getId()));
                ps.setLong(2, entry.getTenantId());
                if (entry.getUserId() != null) {
                    ps.setLong(3, entry.getUserId());
                } else {
                    ps.setNull(3, Types.BIGINT);
                }
                ps.setString(4, entry.getContent());
                ps.setString(5, entry.getEmbeddingJson());
                ps.setString(6, entry.getMetadataJson());
                ps.setTimestamp(7, Timestamp.valueOf(entry.getCreatedAt()));
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("保存记忆失败", e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Flux<MemoryEntry> findByTenant(long tenantId, String query, int limit) {
        return Mono.fromCallable(() -> {
            List<MemoryEntry> results = new ArrayList<>();
            String sql;
            if (query != null && !query.isEmpty()) {
                sql = "SELECT id, tenant_id, user_id, content, embedding_json, metadata_json, created_at FROM t_memory_entry WHERE tenant_id = ? AND MATCH(content) AGAINST(? IN BOOLEAN MODE) LIMIT ?";
            } else {
                sql = "SELECT id, tenant_id, user_id, content, embedding_json, metadata_json, created_at FROM t_memory_entry WHERE tenant_id = ? LIMIT ?";
            }
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, tenantId);
                if (query != null && !query.isEmpty()) {
                    ps.setString(2, query);
                    ps.setInt(3, limit);
                } else {
                    ps.setInt(2, limit);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        results.add(mapEntry(rs));
                    }
                }
            } catch (SQLException e) {
                // FULLTEXT 搜索失败时回退到 LIKE
                if (query != null && !query.isEmpty()) {
                    return findWithLike(tenantId, query, limit);
                }
                throw new RuntimeException("查询记忆失败", e);
            }
            return results;
        }).subscribeOn(Schedulers.boundedElastic()).flatMapMany(Flux::fromIterable);
    }

    @Override
    public Flux<MemoryEntry> findByUser(long tenantId, long userId, String query, int limit) {
        return Mono.fromCallable(() -> {
            List<MemoryEntry> results = new ArrayList<>();
            String sql = "SELECT id, tenant_id, user_id, content, embedding_json, metadata_json, created_at FROM t_memory_entry WHERE tenant_id = ? AND user_id = ?";
            if (query != null && !query.isEmpty()) {
                sql += " AND content LIKE ?";
            }
            sql += " LIMIT ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, tenantId);
                ps.setLong(2, userId);
                int idx = 3;
                if (query != null && !query.isEmpty()) {
                    ps.setString(idx++, "%" + query + "%");
                }
                ps.setInt(idx, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        results.add(mapEntry(rs));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("查询用户记忆失败", e);
            }
            return results;
        }).subscribeOn(Schedulers.boundedElastic()).flatMapMany(Flux::fromIterable);
    }

    @Override
    public Mono<Void> deleteById(String id) {
        return Mono.fromRunnable(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM t_memory_entry WHERE id = ?")) {
                ps.setLong(1, Long.parseLong(id));
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("删除记忆失败", e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<Void> deleteByTenant(long tenantId) {
        return Mono.fromRunnable(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM t_memory_entry WHERE tenant_id = ?")) {
                ps.setLong(1, tenantId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("清空租户记忆失败", e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private List<MemoryEntry> findWithLike(long tenantId, String query, int limit) {
        List<MemoryEntry> results = new ArrayList<>();
        String sql = "SELECT id, tenant_id, user_id, content, embedding_json, metadata_json, created_at FROM t_memory_entry WHERE tenant_id = ? AND content LIKE ? LIMIT ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.setString(2, "%" + query + "%");
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapEntry(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询记忆失败", e);
        }
        return results;
    }

    private MemoryEntry mapEntry(ResultSet rs) throws SQLException {
        return new MemoryEntry(
            String.valueOf(rs.getLong("id")),
            rs.getLong("tenant_id"),
            rs.getObject("user_id") != null ? rs.getLong("user_id") : null,
            rs.getString("content"),
            rs.getString("embedding_json"),
            rs.getString("metadata_json"),
            rs.getTimestamp("created_at").toLocalDateTime()
        );
    }
}
