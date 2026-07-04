package cd.lan1akea.core.tool.builtin;

import cd.lan1akea.core.CoreConstants;
import cd.lan1akea.core.intervention.InterventionRequest;
import cd.lan1akea.core.intervention.InterventionStore;
import cd.lan1akea.core.tool.ToolBase;
import cd.lan1akea.core.tool.ToolCallContext;
import cd.lan1akea.core.tool.ToolResult;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 解决人工介入请求。支持 approve/deny/clarify/reply 四种操作。
 *
 * <p>通过 {@code resolve_intervention} 工具调用，
 * 对指定介入记录执行批准、拒绝、澄清或回复操作。
 */
public class ResolveInterventionTool extends ToolBase {

    private final InterventionStore store;

    /**
     * 创建 ResolveInterventionTool 实例。
     *
     * @param store 介入存储实现
     */
    public ResolveInterventionTool(InterventionStore store) {
        this.store = store;
        declareStringParam(CoreConstants.Intervention.PARAM_INTERVENTION_ID,
                "介入记录ID（从 list_interventions 获取）", true);
        declareStringParam(CoreConstants.Intervention.PARAM_ACTION,
                "操作: approve/deny/clarify/reply", true);
        declareStringParam(CoreConstants.Intervention.PARAM_COMMENT,
                "备注（可选）", false);
    }

    @Override
    public String getName() { return "resolve_intervention"; }

    @Override
    public String getDescription() {
        return "解决一条人工介入请求。参数: intervention_id(介入ID), action(approve/deny/clarify/reply), comment(可选备注)";
    }

    /**
     * 执行介入解决操作。
     *
     * <p>根据传入的 action 参数分发到对应的存储操作：
     * <ul>
     *   <li>approve — 批准介入并恢复执行</li>
     *   <li>deny — 拒绝介入，向 Agent 反馈拒绝消息</li>
     *   <li>clarify — 澄清并修正参数后恢复执行</li>
     *   <li>reply — 回复意见后放行（采用 approve 逻辑）</li>
     * </ul>
     *
     * @param params 工具调用参数（含 intervention_id、action、comment）
     * @return 操作结果的 Mono
     */
    @Override
    public Mono<ToolResult> execute(ToolCallContext params) {
        validateParams(params);
        String id = params.getString(CoreConstants.Intervention.PARAM_INTERVENTION_ID);
        String action = params.getString(CoreConstants.Intervention.PARAM_ACTION);
        String comment = params.getString(CoreConstants.Intervention.PARAM_COMMENT);
        String resolver = params.getUserId() != null ? params.getUserId() : CoreConstants.Intervention.DEFAULT_RESOLVER;

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
