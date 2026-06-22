package cd.lan1akea.core.tool.builtin;

import cd.lan1akea.core.tool.ToolBase;
import cd.lan1akea.core.tool.ToolCallParam;
import cd.lan1akea.core.tool.ToolResult;
import reactor.core.publisher.Mono;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件读取工具。
 */
public class FileReadTool extends ToolBase {

    private static final int MAX_READ_SIZE = 50_000; // 最多读取50KB

    public FileReadTool() {
        declareStringParam("path", "文件路径", true);
        declareStringParam("encoding", "文件编码", false);
    }

    @Override
    public String getName() { return "file_read"; }

    @Override
    public String getDescription() { return "读取文件内容，限制读取" + MAX_READ_SIZE / 1000 + "KB以内"; }

    @Override
    public Mono<ToolResult> execute(ToolCallParam params) {
        return Mono.fromCallable(() -> {
            validateParams(params);
            String pathStr = params.getString("path");
            String encoding = params.getString("encoding");
            if (encoding == null) encoding = "UTF-8";

            try {
                Path path = Path.of(pathStr);
                if (!Files.exists(path)) {
                    return ToolResult.failure("文件不存在: " + pathStr);
                }
                if (!Files.isRegularFile(path)) {
                    return ToolResult.failure("路径不是文件: " + pathStr);
                }
                if (Files.size(path) > MAX_READ_SIZE) {
                    return ToolResult.failure("文件过大，限制 " + MAX_READ_SIZE / 1000 + "KB");
                }
                String content = Files.readString(path, Charset.forName(encoding));
                return ToolResult.success(content);
            } catch (Exception e) {
                return ToolResult.failure("读取失败: " + e.getMessage());
            }
        });
    }
}
