package cd.lan1akea.bootstrap.tool;

import cd.lan1akea.core.tool.*;
import reactor.core.publisher.Mono;

/**
 * 转账工具（演示审批流程）。
 * 金额超过 10000 时需要人工审批，风险等级 HIGH。
 */
public class TransferTool extends ToolBase {

    public TransferTool() {
        declareStringParam("target", "收款账户", true);
        declareNumberParam("amount", "转账金额", true);
    }

    @Override
    public String getName() { return "transfer"; }

    @Override
    public String getDescription() { return "转账工具。向指定账户转账指定金额，金额超过10000时需要审批"; }

    @Override
    public boolean requiresApproval() { return true; }

    @Override
    public String getRiskLevel() { return "HIGH"; }

    @Override
    public Mono<ToolResult> execute(ToolCallContext params) {
        validateParams(params);
        String target = params.getString("target");
        Number amount = params.getNumber("amount");
        return Mono.just(ToolResult.success(
            "转账成功: 已向 " + target + " 转账 " + amount + " 元 [模拟]"));
    }
}
