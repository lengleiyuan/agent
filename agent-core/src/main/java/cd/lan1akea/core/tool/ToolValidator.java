package cd.lan1akea.core.tool;

import cd.lan1akea.core.model.ToolSchema;

import java.util.List;
import java.util.Map;

/**
 * 工具参数校验器。
 * 仅校验必填参数是否存在，类型转换由 {@link cd.lan1akea.core.util.ArgumentConverter} 统一处理。
 */
@SuppressWarnings("unchecked")
public class ToolValidator {

    /**
     * 校验工具调用参数——仅检查必填参数是否存在。
     *
     * @param schema    工具 Schema
     * @param callParam 实际调用参数
     * @throws IllegalArgumentException 如果缺少必填参数
     */
    public void validate(ToolSchema schema, ToolCallContext callParam) {
        Map<String, Object> paramsSchema = schema.getParametersSchema();
        Map<String, Object> properties = (Map<String, Object>) paramsSchema.get("properties");
        if (properties == null) return;

        List<String> required = (List<String>) paramsSchema.get("required");
        if (required == null) return;

        for (String field : required) {
            if (callParam.get(field) == null) {
                throw new IllegalArgumentException(
                    "工具 [" + schema.getName() + "] 缺少必填参数: " + field);
            }
        }
    }
}
