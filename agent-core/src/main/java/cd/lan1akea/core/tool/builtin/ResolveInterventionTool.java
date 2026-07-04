package cd.lan1akea.core.tool.builtin;

import cd.lan1akea.core.intervention.InterventionRequest;
import cd.lan1akea.core.intervention.InterventionStore;
import cd.lan1akea.core.tool.ToolBase;
import cd.lan1akea.core.tool.ToolCallContext;
import cd.lan1akea.core.tool.ToolResult;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 解决人工介入请求。支持 approve/deny/clarify/reply 四种操作。
 */
public class ResolveInterventionTool extends ToolBase {

    private final InterventionStore store;

    public ResolveInterventionTool(InterventionStore store) {
        this.store = store;
        declareStringParam("intervention_id", "介入记录ID（从 list_interventions 获取）", true);
        declareStringParam("action", "操作: approve/deny/clarify/reply", true);
        declareStringParam("comment", "备注（可选）", false);
    }

    @Override
    public String getName() { return "resolve_intervention"; }

    @Override
    public String getDescription() {
        return "解决一条人工介入请求。参数: intervention_id(介入ID), action(approve/deny/clarify/reply), comment(可选备注)";
    }

    @Override
    public Mono<ToolResult> execute(ToolCallContext params) {
        validateParams(params);
        String id = params.getString("intervention_id");
        String action = params.getString("action");
        String comment = params.getString("comment");
        String resolver = params.getUserId() != null ? params.getUserId() : "resolver";

        InterventionRequest req = store.getById(id);
        if (req == null) {
            return Mono.just(ToolResult.failure("介入记录不存在: " + id));
        }
        if (req.getStatus() != InterventionRequest.Status.PENDING) {
            return Mono.just(ToolResult.failure("该介入已处理，当前状态: " + req.getStatus()));
        }

        switch (action.toLowerCase()) {
            case "approve":
                store.approve(id, resolver, comment != null ? comment : "");
                return Mono.just(ToolResult.success("已批准: " + req.getQuestion()));
            case "deny":
                store.deny(id, resolver, comment != null ? comment : "");
                return Mono.just(ToolResult.success("已拒绝: " + req.getQuestion()));
            case "clarify":
                store.clarify(id, resolver, comment != null ? comment : "", null);
                return Mono.just(ToolResult.success("已澄清: " + req.getQuestion()));
            case "reply":
                store.approve(id, resolver, comment != null ? comment : "");
                return Mono.just(ToolResult.success("已回复: " + (comment != null ? comment : "")));
            default:
                return Mono.just(ToolResult.failure("无效操作: " + action + "。请使用 approve/deny/clarify/reply"));
        }
    }
}
