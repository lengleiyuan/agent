package cd.lan1akea.bootstrap.tool;

import cd.lan1akea.core.exception.HumanInterventionException;
import cd.lan1akea.core.tool.*;
import reactor.core.publisher.Mono;

/**
 * 转账工具（演示审批流程）。
 * 金额超过 10000 时抛出 HumanInterventionException 触发审批，风险等级 HIGH。
 */
public class TransferTool extends ToolBase {

    private static final long MAX_AUTO_AMOUNT = 10000;

    public TransferTool() {
        declareStringParam("target", "收款账户名称", true);
        declareRangedNumberParam("amount", "转账金额（最小1元）", true, 1.0, null);
    }

    @Override
    public String getName() { return "transfer"; }

    @Override
    public String getDescription() { return "转账工具。向指定账户转账指定金额，金额超过10000时需要审批"; }

    @Override
    public String getRiskLevel() { return "HIGH"; }

    @Override
    public Mono<ToolResult> execute(ToolCallContext params) {
        validateParams(params);
        String target = params.getString("target");
        Number amount = params.getNumber("amount");

        if (!params.isApproved() && amount != null && amount.longValue() > MAX_AUTO_AMOUNT) {
            throw HumanInterventionException.approval("transfer",
                "转账金额 " + amount + " 超过 " + MAX_AUTO_AMOUNT + " 上限，是否继续？",
                params).withTtlMinutes(2);
        }

        return Mono.just(ToolResult.success(
            "转账成功: 已向 " + target + " 转账 " + amount + " 元 [模拟]"));
    }
}
