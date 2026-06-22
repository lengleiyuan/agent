package cd.lan1akea.core.message;

import java.util.List;
import java.util.Map;

/**
 * 用户消息。
 * <p>
 * 角色固定为 USER。可以包含文本、图片等多种内容块。
 * </p>
 */
public class UserMessage extends Msg {

    public UserMessage(List<ContentBlock> contentBlocks, Map<String, Object> metadata) {
        super(MsgRole.USER, contentBlocks, metadata);
    }

    /**
     * 创建仅包含文本的用户消息。
     *
     * @param text 用户输入文本
     * @return UserMessage 实例
     */
    public static UserMessage of(String text) {
        return (UserMessage) Msg.builder(MsgRole.USER)
            .addText(text)
            .build();
    }

    /**
     * 创建用户消息构建器。
     *
     * @return MsgBuilder（角色为USER）
     */
    public static MsgBuilder builder() {
        return Msg.builder(MsgRole.USER);
    }
}
