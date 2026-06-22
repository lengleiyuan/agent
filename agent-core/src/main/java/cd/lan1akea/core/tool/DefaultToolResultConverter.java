package cd.lan1akea.core.tool;

/**
 * 默认结果转换器。
 * <p>
 * 成功：直接返回内容；失败：返回 "[错误] errorMessage"。
 * </p>
 */
public class DefaultToolResultConverter implements ToolResultConverter {

    @Override
    public String convert(ToolResult result) {
        if (result.isSuccess()) {
            return result.getContent();
        }
        return "[错误] " + (result.getErrorMessage() != null
            ? result.getErrorMessage() : "未知错误");
    }
}
