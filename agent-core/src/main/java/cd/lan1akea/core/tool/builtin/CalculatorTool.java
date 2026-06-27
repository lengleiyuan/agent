package cd.lan1akea.core.tool.builtin;

import cd.lan1akea.core.tool.ToolBase;
import cd.lan1akea.core.tool.ToolCallContext;
import cd.lan1akea.core.tool.ToolResult;
import reactor.core.publisher.Mono;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

/**
 * 数学计算器工具。
 * 使用 javax.script 引擎评估数学表达式。
 */
public class CalculatorTool extends ToolBase {

    // 每次 execute 新建 ScriptEngine，保证线程安全（javax.script 的 ScriptEngine 非线程安全）
    private static ScriptEngine createEngine() {
        return new ScriptEngineManager().getEngineByName("JavaScript");
    }

    /**
     * 计算器工具构造函数，声明 expression 为必填字符串参数。
     */
    public CalculatorTool() {
        declareStringParam("expression", "数学表达式，如 2+3*4", true);
    }

    @Override
    public String getName() { return "calculator"; }

    @Override
    public String getDescription() { return "计算数学表达式，支持加减乘除、括号、幂运算等"; }

    @Override
    public Mono<ToolResult> execute(ToolCallContext params) {
        return Mono.fromCallable(() -> {
            validateParams(params);
            String expr = params.getString("expression");
            if (expr == null || expr.isBlank()) {
                return ToolResult.failure("表达式不能为空");
            }
            if (expr.contains("System") || expr.contains("Runtime")
                || expr.contains("exec") || expr.contains("ProcessBuilder")) {
                return ToolResult.failure("表达式包含不安全的操作");
            }
            try {
                Object result = createEngine().eval(expr);
                return ToolResult.success(String.valueOf(result));
            } catch (Exception e) {
                return ToolResult.failure("计算错误: " + e.getMessage());
            }
        });
    }
}
