package cd.lan1akea.core.tool.builtin;

import cd.lan1akea.core.intervention.InterventionRequest;
import cd.lan1akea.core.intervention.InterventionStore;
import cd.lan1akea.core.tool.ToolBase;
import cd.lan1akea.core.tool.ToolCallContext;
import cd.lan1akea.core.tool.ToolResult;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 列出所有待处理的人工介入请求。
 */
public class ListInterventionsTool extends ToolBase {

    private final InterventionStore store;

    public ListInterventionsTool(InterventionStore store) {
        this.store = store;
    }

    @Override
    public String getName() { return "list_interventions"; }

    @Override
    public String getDescription() {
        return "列出所有待处理的人工介入请求。返回每条介入的intervention_id、类型、工具名、问题描述";
    }

    @Override
    public Mono<ToolResult> execute(ToolCallContext params) {
        List<InterventionRequest> pending = store.getAllPending();
        if (pending.isEmpty()) {
            return Mono.just(ToolResult.success("当前无待处理的人工介入请求"));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("以下 ").append(pending.size()).append(" 条介入请求正在等待处理：\n\n");
        for (int i = 0; i < pending.size(); i++) {
            InterventionRequest r = pending.get(i);
            sb.append("[").append(i + 1).append("] ");
            sb.append("intervention_id: ").append(r.getInterventionId()).append("\n");
            sb.append("    类型: ").append(r.getType()).append("\n");
            sb.append("    会话: ").append(r.getSessionId()).append("\n");
            sb.append("    Agent: ").append(r.getAgentName()).append("\n");
            if (r.getToolName() != null) sb.append("    工具: ").append(r.getToolName()).append("\n");
            sb.append("    问题: ").append(r.getQuestion()).append("\n");
            if (r.getToolArgs() != null && !r.getToolArgs().isEmpty()) {
                sb.append("    参数: ").append(r.getToolArgs()).append("\n");
            }
            sb.append("\n");
        }
        sb.append("使用 resolve_intervention 工具处理，参数 intervention_id 和 action（approve/deny/clarify/reply）");
        return Mono.just(ToolResult.success(sb.toString().trim()));
    }
}
