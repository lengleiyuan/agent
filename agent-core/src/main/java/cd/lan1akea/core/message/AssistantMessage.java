package cd.lan1akea.core.message;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 助手（AI）消息。
 * 角色固定为 ASSISTANT。可以包含文本回复、工具调用、思考过程等内容块。
 */
public class AssistantMessage extends Msg {

    /**
     * 创建带有指定内容块和元数据的助手消息。
     *
     * @param contentBlocks 内容块列表
     * @param metadata      元数据映射
     */
    public AssistantMessage(List<ContentBlock> contentBlocks, Map<String, Object> metadata) {
        super(MsgRole.ASSISTANT, contentBlocks, metadata);
    }

    /**
     * 创建仅包含文本的助手消息。
     *
     * @param text 助手回复文本
 * @return AssistantMessage 实例
     */
    public static AssistantMessage of(String text) {
        return new AssistantMessage(List.of(new TextBlock(text)), Collections.emptyMap());
    }

    /**
     * 创建助手消息构建器。
     *
     * @return MsgBuilder（角色为ASSISTANT）
     */
    public static MsgBuilder builder() {
        return Msg.builder(MsgRole.ASSISTANT);
    }
}
