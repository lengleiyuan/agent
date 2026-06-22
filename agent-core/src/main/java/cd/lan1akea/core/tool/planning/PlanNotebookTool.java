package cd.lan1akea.core.tool.planning;

import cd.lan1akea.core.tool.ToolBase;
import cd.lan1akea.core.tool.ToolCallParam;
import cd.lan1akea.core.tool.ToolResult;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 任务规划工具。
 * <p>
 * 将复杂目标分解为有序步骤，跟踪执行进度。
 * </p>
 */
public class PlanNotebookTool extends ToolBase {

    /** 共享的计划存储（planName → 步骤列表） */
    static final Map<String, List<PlanStep>> plans = new ConcurrentHashMap<>();

    public PlanNotebookTool() {
        declareStringParam("action", "操作: create/add_step/complete_step/list/cancel", true);
        declareStringParam("plan_name", "计划名称", true);
        declareStringParam("step_description", "步骤描述", false);
    }

    @Override
    public String getName() { return "plan_notebook"; }

    @Override
    public String getDescription() { return "创建和管理任务计划，将复杂目标分解为有序步骤"; }

    @Override
    public Mono<ToolResult> execute(ToolCallParam params) {
        return Mono.fromCallable(() -> {
            validateParams(params);
            String action = params.getString("action");
            String planName = params.getString("plan_name");

            switch (action) {
                case "create":
                    return createPlan(planName);
                case "add_step":
                    String desc = params.getString("step_description");
                    return addStep(planName, desc);
                case "complete_step":
                    return completeStep(planName);
                case "list":
                    return listPlan(planName);
                case "cancel":
                    return cancelPlan(planName);
                default:
                    return ToolResult.failure("未知操作: " + action
                        + "，可用: create/add_step/complete_step/list/cancel");
            }
        });
    }

    private ToolResult createPlan(String planName) {
        if (plans.containsKey(planName)) {
            return ToolResult.failure("计划已存在: " + planName);
        }
        plans.put(planName, new ArrayList<>());
        return ToolResult.success("计划 [" + planName + "] 创建成功");
    }

    private ToolResult addStep(String planName, String description) {
        List<PlanStep> steps = plans.get(planName);
        if (steps == null) {
            return ToolResult.failure("计划不存在: " + planName + "，请先 create");
        }
        if (description == null || description.isBlank()) {
            return ToolResult.failure("步骤描述不能为空");
        }
        PlanStep step = new PlanStep(steps.size() + 1, description);
        steps.add(step);
        return ToolResult.success("步骤 " + step.index + " 已添加: " + description);
    }

    private ToolResult completeStep(String planName) {
        List<PlanStep> steps = plans.get(planName);
        if (steps == null) return ToolResult.failure("计划不存在: " + planName);

        for (PlanStep step : steps) {
            if ("pending".equals(step.status)) {
                step.status = "completed";
                return ToolResult.success("步骤 " + step.index + " [" + step.description + "] 已完成");
            }
        }
        return ToolResult.success("所有步骤已完成！");
    }

    private ToolResult listPlan(String planName) {
        if (planName.equals("*")) {
            // 列出所有计划
            String all = plans.entrySet().stream()
                .map(e -> e.getKey() + " (" + e.getValue().size() + " 步骤, "
                    + e.getValue().stream().filter(s -> "completed".equals(s.status)).count()
                    + " 已完成)")
                .collect(Collectors.joining("\n"));
            return ToolResult.success(all.isEmpty() ? "无计划" : all);
        }
        List<PlanStep> steps = plans.get(planName);
        if (steps == null) return ToolResult.failure("计划不存在: " + planName);

        String list = steps.stream()
            .map(s -> "[" + s.status + "] 步骤" + s.index + ": " + s.description)
            .collect(Collectors.joining("\n"));
        return ToolResult.success(list.isEmpty() ? "计划为空" : list);
    }

    private ToolResult cancelPlan(String planName) {
        List<PlanStep> removed = plans.remove(planName);
        if (removed == null) return ToolResult.failure("计划不存在: " + planName);
        return ToolResult.success("计划 [" + planName + "] 已取消");
    }

    /** 计划步骤 */
    static class PlanStep {
        final int index;
        final String description;
        String status;

        PlanStep(int index, String description) {
            this.index = index;
            this.description = description;
            this.status = "pending";
        }
    }
}
