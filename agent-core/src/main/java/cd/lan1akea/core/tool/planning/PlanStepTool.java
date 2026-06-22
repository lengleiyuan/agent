package cd.lan1akea.core.tool.planning;

import cd.lan1akea.core.tool.ToolBase;
import cd.lan1akea.core.tool.ToolCallParam;
import cd.lan1akea.core.tool.ToolResult;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 步骤管理工具。
 * <p>
 * 与 PlanNotebookTool 共享计划存储，更新单个步骤状态。
 * </p>
 */
public class PlanStepTool extends ToolBase {

    public PlanStepTool() {
        declareStringParam("plan_name", "计划名称", true);
        declareNumberParam("step_index", "步骤序号", true);
        declareStringParam("new_status", "新状态: pending/in_progress/completed/skipped", true);
    }

    @Override
    public String getName() { return "plan_step"; }

    @Override
    public String getDescription() { return "更新任务计划中单个步骤的状态"; }

    @Override
    public Mono<ToolResult> execute(ToolCallParam params) {
        return Mono.fromCallable(() -> {
            validateParams(params);
            String planName = params.getString("plan_name");
            int stepIndex = params.getNumber("step_index").intValue();
            String newStatus = params.getString("new_status");

            List<PlanNotebookTool.PlanStep> steps = PlanNotebookTool.plans.get(planName);
            if (steps == null) {
                return ToolResult.failure("计划不存在: " + planName);
            }
            if (stepIndex < 1 || stepIndex > steps.size()) {
                return ToolResult.failure("步骤序号无效: " + stepIndex
                    + "（有效范围: 1-" + steps.size() + "）");
            }

            PlanNotebookTool.PlanStep step = steps.get(stepIndex - 1);
            step.status = newStatus;
            return ToolResult.success("步骤 " + stepIndex + " 状态已更新为: " + newStatus);
        });
    }
}
