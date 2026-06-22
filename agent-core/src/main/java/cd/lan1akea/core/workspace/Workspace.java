package cd.lan1akea.core.workspace;

import java.nio.file.Path;

/**
 * 工作空间（文件沙箱）。
 * <p>
 * 管理 Agent 的文件读写操作边界，防止访问沙箱外的文件。
 * </p>
 */
public class Workspace {

    private final Path rootPath;
    private final long tenantId;

    public Workspace(Path rootPath, long tenantId) {
        this.rootPath = rootPath;
        this.tenantId = tenantId;
    }

    /**
     * 校验路径是否在工作空间内。
     *
     * @param path 待校验的文件路径
     * @return 规范化后的路径
     * @throws SecurityException 如果路径在沙箱外
     */
    public Path resolvePath(String path) {
        Path resolved = rootPath.resolve(path).normalize();
        if (!resolved.startsWith(rootPath)) {
            throw new SecurityException(
                "路径越界: " + path + " → " + resolved + "，工作空间: " + rootPath);
        }
        return resolved;
    }

    /** @return 工作空间根路径 */
    public Path getRootPath() { return rootPath; }

    /** @return 租户ID */
    public long getTenantId() { return tenantId; }
}
