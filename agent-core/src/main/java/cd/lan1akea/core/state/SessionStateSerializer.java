package cd.lan1akea.core.state;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.util.JsonUtils;

import java.util.List;

/**
 * 会话状态序列化工具。
 */
public final class SessionStateSerializer {

    private SessionStateSerializer() { }

    /** 序列化消息列表 */
    public static String serializeMessages(List<Msg> messages) {
        return JsonUtils.toCompactJson(messages);
    }

    /** 反序列化消息列表 */
    public static List<Msg> deserializeMessages(String json) {
        return JsonUtils.fromJson(json,
            new com.alibaba.fastjson2.TypeReference<List<Msg>>() {}.getType());
    }

    /** 序列化 AgentState */
    public static String serialize(AgentState state) {
        return JsonUtils.toCompactJson(state);
    }

    /** 反序列化 AgentState */
    public static AgentState deserialize(String json) {
        return JsonUtils.fromJson(json, AgentState.class);
    }
}
