package cd.lan1akea.core.message;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 系统消息。
 * 用于设定 Agent 的行为、角色、约束条件等。
 * 角色固定为 SYSTEM。
 */
public class SystemMessage extends Msg {

    /**
     * 创建带有指定内容块和元数据的系统消息。
     *
     * @param contentBlocks 内容块列表
     * @param metadata      元数据映射
     */
    public SystemMessage(List<ContentBlock> contentBlocks, Map<String, Object> metadata) {
        super(MsgRole.SYSTEM, contentBlocks, metadata);
    }

    /**
     * 创建仅包含文本的系统消息。
     *
     * @param systemPrompt 系统提示文本
     * @return SystemMessage 实例
     */
    public static SystemMessage of(String systemPrompt) {
        return new SystemMessage(List.of(new TextBlock(systemPrompt)), Collections.emptyMap());
    }

    /**
     * 创建系统消息构建器。
     *
     * @return MsgBuilder（角色为SYSTEM）
     */
    public static MsgBuilder builder() {
        return Msg.builder(MsgRole.SYSTEM);
    }
}
