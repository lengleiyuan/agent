package cd.lan1akea.core.memory;

import java.time.LocalDateTime;

/**
 * 记忆条目。
 */
public class MemoryEntry {

    private final String id;
    private final long tenantId;
    private final Long userId;
    private final String content;
    private final String embeddingJson;
    private final String metadataJson;
    private final LocalDateTime createdAt;

    public MemoryEntry(String id, long tenantId, Long userId, String content,
                        String embeddingJson, String metadataJson, LocalDateTime createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.content = content;
        this.embeddingJson = embeddingJson;
        this.metadataJson = metadataJson;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public long getTenantId() { return tenantId; }
    public Long getUserId() { return userId; }
    public String getContent() { return content; }
    public String getEmbeddingJson() { return embeddingJson; }
    public String getMetadataJson() { return metadataJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
