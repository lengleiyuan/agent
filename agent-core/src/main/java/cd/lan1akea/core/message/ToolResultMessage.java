package cd.lan1akea.core.message;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 工具结果消息。
 * <p>
 * 角色固定为 TOOL。包含工具执行后的返回结果。
 * </p>
 */
public class ToolResultMessage extends Msg {

    public ToolResultMessage(List<ContentBlock> contentBlocks, Map<String, Object> metadata) {
        super(MsgRole.TOOL, contentBlocks, metadata);
    }

    /**
     * 创建包含单个工具结果的工具消息。
     *
     * @param toolUseId  工具调用ID
     * @param result     执行结果
     * @param isError    是否出错
     * @return ToolResultMessage 实例
     */
    public static ToolResultMessage of(String toolUseId, String result, boolean isError) {
        return new ToolResultMessage(
            List.of(new ToolResultBlock(toolUseId, result, isError)),
            Collections.emptyMap());
    }

    /**
     * 创建工具结果消息构建器。
     *
     * @return MsgBuilder（角色为TOOL）
     */
    public static MsgBuilder builder() {
        return Msg.builder(MsgRole.TOOL);
    }
}
