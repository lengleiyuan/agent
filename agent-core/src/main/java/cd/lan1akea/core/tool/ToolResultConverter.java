package cd.lan1akea.core.tool;

/**
 * 工具结果转换器接口。
 * <p>
 * 将工具执行结果（ToolResult）转换为最终返回给 LLM 的字符串格式。
 * </p>
 */
@FunctionalInterface
public interface ToolResultConverter {

    /**
     * 转换工具结果。
     *
     * @param result 原始执行结果
     * @return 转换后的字符串
     */
    String convert(ToolResult result);
}
