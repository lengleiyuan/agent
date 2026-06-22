package cd.lan1akea.core.tool;

import cd.lan1akea.core.agent.Agent;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.MsgRole;
import cd.lan1akea.core.message.UserMessage;
import cd.lan1akea.core.model.ToolSchema;
import cd.lan1akea.core.util.JsonUtils;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具。
 * <p>
 * 将另一个 Agent 包装为 Tool，实现 Agent-as-Tool 模式。
 * 调用此工具时，会启动子 Agent 处理请求并返回结果。
 * </p>
 */
public class AgentTool implements Tool {

    private final Agent agent;

    public AgentTool(Agent agent) {
        this.agent = agent;
    }

    @Override
    public String getName() {
        return "agent_" + agent.getName().toLowerCase().replace(" ", "_");
    }

    @Override
    public String getDescription() {
        return "委托给子 Agent [" + agent.getName() + "] 处理";
    }

    @Override
    public ToolSchema getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> inputProp = new LinkedHashMap<>();
        inputProp.put("type", "string");
        inputProp.put("description", "传递给子 Agent 的输入");
        properties.put("input", inputProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("input"));

        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public Mono<ToolResult> execute(ToolCallParam params) {
        String input = params.getString("input");
        if (input == null || input.isEmpty()) {
            return Mono.just(ToolResult.failure("子 Agent 输入不能为空"));
        }

        // 构造用户消息并发送给子 Agent
        Msg userMsg = UserMessage.of(input);
        return agent.chat(List.of(userMsg), null)
            .map(response -> ToolResult.success(
                JsonUtils.toCompactJson(response.getMessage())))
            .onErrorResume(e ->
                Mono.just(ToolResult.failure("子 Agent 执行失败: " + e.getMessage())));
    }

    @Override
    public long getTimeoutMs() {
        return 120000; // 子Agent调用超时2分钟
    }
}
