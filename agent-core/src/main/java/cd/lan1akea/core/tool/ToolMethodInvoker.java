package cd.lan1akea.core.tool;

import reactor.core.publisher.Mono;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 反射工具方法调用器。
 * <p>
 * 支持将任意 Java 方法包装为 Tool.execute() 调用。
 * 配合 harness 层的 @ToolFunction 注解使用。
 * </p>
 */
public class ToolMethodInvoker implements Tool {

    private final String name;
    private final String description;
    private final ToolSchemaProvider schemaProvider;
    private final Object targetInstance;
    private final Method method;
    private final ToolParam[] params;

    public ToolMethodInvoker(String name, String description,
                              ToolSchemaProvider schemaProvider,
                              Object targetInstance, Method method,
                              ToolParam[] params) {
        this.name = name;
        this.description = description;
        this.schemaProvider = schemaProvider;
        this.targetInstance = targetInstance;
        this.method = method;
        this.params = params;
    }

    @Override
    public String getName() { return name; }

    @Override
    public String getDescription() { return description; }

    @Override
    public cd.lan1akea.core.model.ToolSchema getParameters() {
        return schemaProvider.provide(this);
    }

    @Override
    public Mono<ToolResult> execute(ToolCallParam callParam) {
        return Mono.fromCallable(() -> {
            try {
                method.setAccessible(true);
                Object result = method.invoke(targetInstance, callParam);
                if (result instanceof Mono) {
                    @SuppressWarnings("unchecked")
                    Mono<ToolResult> monoResult = (Mono<ToolResult>) result;
                    return monoResult;
                }
                return Mono.just(ToolResult.success(
                    result != null ? result.toString() : ""));
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof ToolSuspendException) {
                    throw (ToolSuspendException) cause;
                }
                return Mono.just(ToolResult.failure(
                    "工具 [" + name + "] 执行异常: "
                        + (cause != null ? cause.getMessage() : e.getMessage())));
            } catch (Exception e) {
                return Mono.just(ToolResult.failure(
                    "工具 [" + name + "] 调用失败: " + e.getMessage()));
            }
        }).flatMap(result -> result);
    }
}
