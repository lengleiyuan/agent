package cd.lan1akea.core.tool.builtin;

import cd.lan1akea.core.tool.ToolBase;
import cd.lan1akea.core.tool.ToolCallParam;
import cd.lan1akea.core.tool.ToolResult;
import reactor.core.publisher.Mono;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

/**
 * 数学计算器工具。
 * <p>
 * 使用 javax.script 引擎评估数学表达式。
 * </p>
 */
public class CalculatorTool extends ToolBase {

    private static final ScriptEngine ENGINE =
        new ScriptEngineManager().getEngineByName("JavaScript");

    public CalculatorTool() {
        declareStringParam("expression", "数学表达式，如 2+3*4", true);
    }

    @Override
    public String getName() { return "calculator"; }

    @Override
    public String getDescription() { return "计算数学表达式，支持加减乘除、括号、幂运算等"; }

    @Override
    public Mono<ToolResult> execute(ToolCallParam params) {
        return Mono.fromCallable(() -> {
            validateParams(params);
            String expr = params.getString("expression");
            if (expr == null || expr.isBlank()) {
                return ToolResult.failure("表达式不能为空");
            }
            // 安全检查
            if (expr.contains("System") || expr.contains("Runtime")
                || expr.contains("exec") || expr.contains("ProcessBuilder")) {
                return ToolResult.failure("表达式包含不安全的操作");
            }
            try {
                Object result = ENGINE.eval(expr);
                return ToolResult.success(String.valueOf(result));
            } catch (Exception e) {
                return ToolResult.failure("计算错误: " + e.getMessage());
            }
        });
    }
}
