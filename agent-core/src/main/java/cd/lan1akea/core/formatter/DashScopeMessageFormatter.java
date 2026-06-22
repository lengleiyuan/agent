package cd.lan1akea.core.formatter;

import cd.lan1akea.core.message.*;

import java.util.*;

/**
 * 百炼（DashScope）消息格式化器。
 * <p>
 * 通义千问 API 格式与 OpenAI 略有不同，需特殊处理。
 * </p>
 */
public class DashScopeMessageFormatter implements MessageFormatter {

    @Override
    public List<Map<String, Object>> format(List<Msg> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Msg msg : messages) {
            Map<String, Object> formatted = new LinkedHashMap<>();
            // 百炼使用 message 而非 messages 作为列表元素键名
            formatted.put("role", msg.getRole().getValue());
            String text = msg.getTextContent();
            formatted.put("content", text != null ? text : "");
            result.add(formatted);
        }
        return result;
    }
}
