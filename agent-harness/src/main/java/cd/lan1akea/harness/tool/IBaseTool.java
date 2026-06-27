package cd.lan1akea.harness.tool;

import cd.lan1akea.core.model.ToolSchema;
import cd.lan1akea.core.tool.ToolCallContext;
import cd.lan1akea.core.tool.ToolResult;
import cd.lan1akea.harness.context.ToolContext;
import reactor.core.publisher.Mono;

/**
 * 工具抽象基类——同步执行，框架自动包装为 Mono。
 *
 * 示例：
 *     public class Calculator extends IBaseTool {
 *         public String getName() { return "calculator"; }
 *         public String getDescription() { return "计算数学表达式"; }
 *         public ToolSchema getParameters() { ... }
 *         protected ToolResult doExecute(ToolContext ctx) {
 *             String expr = ctx.getString("expression");
 *             return ToolResult.success(String.valueOf(eval(expr)));
 *         }
 *     }
 */
public abstract class IBaseTool implements ITool {

    /**
     * 返回工具名称。
     */
    @Override
    public abstract String getName();

    /**
     * 返回工具描述。
     */
    @Override
    public abstract String getDescription();

    /**
     * 返回工具参数 Schema。
     */
    @Override
    public abstract ToolSchema getParameters();

    /**
     * 业务实现此方法，同步返回结果。框架自动包装为 Mono。
     */
    protected abstract ToolResult doExecute(ToolContext ctx);

    /**
     * 执行工具调用，同步方法自动转为 Mono。
     */
    @Override
    public Mono<ToolResult> execute(ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(ctx));
    }

    /**
     * core 层适配，将 core 上下文包装为门面 ToolContext。
     */
    @Override
    public Mono<ToolResult> execute(ToolCallContext coreCtx) {
        return execute(new ToolContext(coreCtx));
    }
}
