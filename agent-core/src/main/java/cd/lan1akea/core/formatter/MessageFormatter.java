package cd.lan1akea.core.formatter;

import cd.lan1akea.core.message.Msg;

import java.util.List;
import java.util.Map;

/**
 * 消息格式化接口。
 * <p>
 * 将内部 Msg 列表转换为各 LLM 服务商要求的 JSON 格式。
 * </p>
 */
public interface MessageFormatter {

    /**
     * 格式化消息列表。
     *
     * @param messages 内部消息列表
     * @return 格式化后的 Map 列表（将转为 JSON 发送给 LLM）
     */
    List<Map<String, Object>> format(List<Msg> messages);
}
