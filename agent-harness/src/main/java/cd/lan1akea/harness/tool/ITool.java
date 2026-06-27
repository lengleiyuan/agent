package cd.lan1akea.harness.tool;

import cd.lan1akea.core.model.ToolSchema;
import cd.lan1akea.core.tool.Tool;
import cd.lan1akea.core.tool.ToolCallContext;
import cd.lan1akea.core.tool.ToolResult;
import cd.lan1akea.harness.context.ToolContext;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.Set;

/**
 * 工具接口（门面层）。
 * 业务代码实现此接口创建工具，无需直接依赖 core 层。
 * 框架自动将门面 ToolContext 适配为 core 层参数。
 *
 * 示例：
 *     public class MySearchTool implements ITool {
 *         public String getName() { return "search"; }
 *         public String getDescription() { return "搜索文档"; }
 *         public ToolSchema getParameters() { ... }
 *         public Mono<ToolResult> execute(ToolContext ctx) {
 *             String query = ctx.getString("q");
 *             return Mono.just(ToolResult.success("结果: " + query));
 *         }
 *     }
 */
public interface ITool extends Tool {

    /**
     * 门面层 execute：接收 ToolContext。
     */
    Mono<ToolResult> execute(ToolContext ctx);

    /**
     * 返回工具名称。
     */
    @Override
    String getName();

    /**
     * 返回工具描述。
     */
    @Override
    String getDescription();

    /**
     * 返回工具参数 Schema。
     */
    @Override
    ToolSchema getParameters();

    /**
     * 返回工具分组名称。
     */
    @Override
    default String getGroup() { return "default"; }

    /**
     * 返回是否需要审批。
     */
    @Override
    default boolean requiresApproval() { return false; }

    /**
     * 返回工具调用超时时间（毫秒）。
     */
    @Override
    default long getTimeoutMs() { return 30000; }

    /**
     * 返回工具所需的业务权限码集合。
     */
    @Override
    default Set<String> getPermissions() { return Collections.emptySet(); }

    /**
     * core 层适配——框架内部调用，业务无需关心。
     * 将 core 的 ToolCallContext 包装为门面 ToolContext。
     */
    @Override
    default Mono<ToolResult> execute(ToolCallContext coreCtx) {
        return execute(new ToolContext(coreCtx));
    }
}
