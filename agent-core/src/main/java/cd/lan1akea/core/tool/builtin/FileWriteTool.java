package cd.lan1akea.core.tool.builtin;

import cd.lan1akea.core.tool.ToolBase;
import cd.lan1akea.core.tool.ToolCallParam;
import cd.lan1akea.core.tool.ToolResult;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件写入工具。
 * <p>
 * 需要人工审批（requiresApproval=true）。
 * </p>
 */
public class FileWriteTool extends ToolBase {

    public FileWriteTool() {
        declareStringParam("path", "文件路径", true);
        declareStringParam("content", "写入内容", true);
    }

    @Override
    public String getName() { return "file_write"; }

    @Override
    public String getDescription() { return "写入内容到文件，需要人工审批"; }

    @Override
    public boolean requiresApproval() { return true; }

    @Override
    public Mono<ToolResult> execute(ToolCallParam params) {
        return Mono.fromCallable(() -> {
            validateParams(params);
            String pathStr = params.getString("path");
            String content = params.getString("content");

            try {
                Path path = Path.of(pathStr);
                // 自动创建父目录
                Path parent = path.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }
                Files.writeString(path, content);
                return ToolResult.success("写入成功: " + pathStr + " (" + content.length() + " 字符)");
            } catch (Exception e) {
                return ToolResult.failure("写入失败: " + e.getMessage());
            }
        });
    }
}
