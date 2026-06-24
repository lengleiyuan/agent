package cd.lan1akea.core.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 工作空间。
 * <p>
 * 管理 Agent 的结构化持久化目录和文件。
 * 约定目录结构：
 * <pre>
 * workspace/
 * ├── AGENTS.md              ← 人格与行为约定（自动注入 system prompt）
 * ├── MEMORY.md              ← 长期记忆（可选，业务方写入）
 * ├── memory/                ← 每日记忆流水账
 * ├── plans/                 ← 计划文件
 * └── sessions/              ← 会话存档
 * </pre>
 * 路径隔离：所有文件操作限制在 rootPath 内，防止越界访问。
 * </p>
 */
public class Workspace {

    private final Path rootPath;
    private final long tenantId;

    public Workspace(Path rootPath, long tenantId) {
        this.rootPath = rootPath;
        this.tenantId = tenantId;
    }

    // ========================================================================
    // 路径管理
    // ========================================================================

    /**
     * 校验路径是否在工作空间内。
     */
    public Path resolvePath(String path) {
        Path resolved = rootPath.resolve(path).normalize();
        if (!resolved.startsWith(rootPath)) {
            throw new SecurityException(
                "路径越界: " + path + " → " + resolved + "，工作空间: " + rootPath);
        }
        return resolved;
    }

    // ========================================================================
    // AGENTS.md
    // ========================================================================

    /**
     * 读取 workspace 根目录的 AGENTS.md 文件内容。
     * <p>
     * 该文件定义 Agent 的人格、行为约定、知识背景等，
     * 在每次 chat/stream 调用开始前自动注入为 system prompt。
     * </p>
     *
     * @return AGENTS.md 的文本内容，文件不存在时返回 null
     */
    public String readAgentsMd() {
        Path path = rootPath.resolve("AGENTS.md");
        if (!Files.exists(path)) return null;
        try {
            return Files.readString(path);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 写入 AGENTS.md。
     */
    public void writeAgentsMd(String content) throws IOException {
        Files.writeString(rootPath.resolve("AGENTS.md"), content);
    }

    // ========================================================================
    // MEMORY.md
    // ========================================================================

    /**
     * 读取 MEMORY.md（长期记忆）。
     *
     * @return MEMORY.md 的文本内容，文件不存在时返回 null
     */
    public String readMemoryMd() {
        Path path = rootPath.resolve("MEMORY.md");
        if (!Files.exists(path)) return null;
        try {
            return Files.readString(path);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 写入/追加到 MEMORY.md。
     */
    public void appendMemoryMd(String content) throws IOException {
        Path path = rootPath.resolve("MEMORY.md");
        String existing = readMemoryMd();
        String merged = (existing != null ? existing + "\n" : "") + content;
        Files.writeString(path, merged);
    }

    // ========================================================================
    // 结构化目录
    // ========================================================================

    /** @return memory/ 目录路径 */
    public Path getMemoryDir() { return rootPath.resolve("memory"); }

    /** @return plans/ 目录路径 */
    public Path getPlansDir() { return rootPath.resolve("plans"); }

    /** @return sessions/ 目录路径 */
    public Path getSessionsDir() { return rootPath.resolve("sessions"); }

    /**
     * 确保约定的目录结构存在。
     */
    public void ensureDirectories() throws IOException {
        Files.createDirectories(getMemoryDir());
        Files.createDirectories(getPlansDir());
        Files.createDirectories(getSessionsDir());
    }

    /** @return 工作空间根路径 */
    public Path getRootPath() { return rootPath; }

    /** @return 租户ID */
    public long getTenantId() { return tenantId; }
}
