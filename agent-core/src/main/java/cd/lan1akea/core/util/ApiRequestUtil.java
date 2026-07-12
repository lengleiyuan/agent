package cd.lan1akea.core.util;

import cd.lan1akea.core.CoreConstants.ApiFormat;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.ToolSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API 请求体构建工具。
 *
 * <p>ChatModelBase 和 StreamTokenEstimationHook 共用，
 * 保证请求 JSON 结构和 token 估算来源一致。
 */
public final class ApiRequestUtil {

    private ApiRequestUtil() {}

    /**
     * 构建工具数组（OpenAI 兼容格式）。
     */
    public static List<Map<String, Object>> buildToolArray(List<ToolSchema> schemas) {
        List<Map<String, Object>> tools = new ArrayList<>();
        if (schemas == null) return tools;
        for (ToolSchema s : schemas) {
            Map<String, Object> func = new LinkedHashMap<>();
            func.put(ApiFormat.NAME, s.getName());
            func.put(ApiFormat.DESCRIPTION, s.getDescription());
            func.put(ApiFormat.PARAMETERS, s.getParametersSchema());
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put(ApiFormat.TYPE, ApiFormat.TYPE_FUNCTION);
            tool.put(ApiFormat.FUNCTION, func);
            tools.add(tool);
        }
        return tools;
    }

    /**
     * 构建消息 JSON 数组（OpenAI 兼容格式）。
     */
    public static List<Map<String, Object>> buildMessageArray(List<Msg> messages) {
        List<Map<String, Object>> arr = new ArrayList<>();
        if (messages == null) return arr;
        for (Msg m : messages) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put(ApiFormat.ROLE, m.getRole().name().toLowerCase());
            msg.put(ApiFormat.CONTENT, m.getTextContent());
            arr.add(msg);
        }
        return arr;
    }

    /**
     * 构建完整请求体 JSON（对齐 buildCommonRequestBody）。
     *
     * @param messages 已格式化的消息（MessageFormatter 输出）
     * @param schemas  工具 Schema 列表
     * @return 请求体 JSON 字符串
     */
    public static String buildRequestBodyJson(List<Map<String, Object>> messages,
                                               List<ToolSchema> schemas) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(ApiFormat.MESSAGES, messages);
        if (schemas != null && !schemas.isEmpty()) {
            body.put(ApiFormat.TOOLS, buildToolArray(schemas));
            body.put(ApiFormat.TOOL_CHOICE, ApiFormat.TOOL_CHOICE_AUTO);
        }
        body.put(ApiFormat.STREAM, true);
        return JsonUtils.toCompactJson(body);
    }
}
