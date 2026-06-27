package cd.lan1akea.core.tool.builtin;

import cd.lan1akea.core.model.ToolSchema;
import cd.lan1akea.core.tool.Tool;
import cd.lan1akea.core.tool.ToolCallContext;
import cd.lan1akea.core.tool.ToolResult;
import cd.lan1akea.core.util.JsonUtils;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待办任务工具 —— 支持 Agent 在推理过程中创建和跟踪子任务。
 *
 * 用法：LLM 调用 todo_write 记录进度，后续推理可参考。
 */
public class TodoWriteTool implements Tool {

    /**
     * 会话 ID 到待办任务列表的映射
     */
    private final ConcurrentHashMap<String, List<TodoItem>> sessionTodos = new ConcurrentHashMap<>();

    @Override public String getName() { return "todo_write"; }

    @Override
    public String getDescription() {
        return "创建或更新任务列表。参数：todos (JSON数组，每项含id/status/content)。"
            + "status 可选值: pending, in_progress, completed, cancelled";
    }

    @Override
    public ToolSchema getParameters() {
        Map<String, Object> todosProp = Map.of(
            "type", "array",
            "description", "任务列表",
            "items", Map.of("type", "object",
                "properties", Map.of(
                    "id", Map.of("type", "string", "description", "任务ID"),
                    "status", Map.of("type", "string", "description", "pending/in_progress/completed/cancelled"),
                    "content", Map.of("type", "string", "description", "任务描述")
                ))
        );
        return new ToolSchema("todo_write", getDescription(),
            Map.of("type", "object", "properties", Map.of("todos", todosProp)));
    }

    /**
     * 执行待办任务写入操作。
     * 解析传入的 todos JSON 数组，按 sessionId 存储并返回格式化结果。
     *
     * @param params 工具调用参数，需包含 sessionId 和 todos 字段
     * @return 格式化后的任务列表结果
     */
    @Override
    public Mono<ToolResult> execute(ToolCallContext params) {
        String sessionId = params.getSessionId() != null ? params.getSessionId() : "default";
        String todosJson = params.getString("todos");
        if (todosJson == null) {
            return Mono.just(ToolResult.failure("缺少 todos 参数"));
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawTodos = JsonUtils.fromJson(todosJson, List.class);
        if (rawTodos == null) {
            return Mono.just(ToolResult.failure("todos 参数格式错误"));
        }

        List<TodoItem> items = rawTodos.stream()
            .map(m -> new TodoItem(
                (String) m.getOrDefault("id", ""),
                (String) m.getOrDefault("status", "pending"),
                (String) m.getOrDefault("content", "")))
            .toList();

        sessionTodos.put(sessionId, items);

        StringBuilder sb = new StringBuilder("任务列表:\n");
        for (TodoItem item : items) {
            String icon = switch (item.status) {
                case "completed" -> "✅"; case "in_progress" -> "🔄";
                case "cancelled" -> "❌"; default -> "⬜";
            };
            sb.append(icon).append(" [").append(item.id).append("] ").append(item.content).append("\n");
        }
        return Mono.just(ToolResult.success(sb.toString()));
    }

    /**
     * 获取指定会话的待办任务列表。
     *
     * @param sessionId 会话 ID
     * @return 待办任务列表，不存在时返回空列表
     */
    public List<TodoItem> getTodos(String sessionId) {
        return sessionTodos.getOrDefault(sessionId, List.of());
    }

    /**
     * 待办任务项。
     *
     * @param id      任务 ID
     * @param status  状态（pending, in_progress, completed, cancelled）
     * @param content 任务描述
     */
    public record TodoItem(String id, String status, String content) {}
}
