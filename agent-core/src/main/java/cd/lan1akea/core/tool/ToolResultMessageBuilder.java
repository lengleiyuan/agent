package cd.lan1akea.core.tool;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.MsgRole;

/**
 * 工具结果消息构建器。
 * <p>
 * 将工具执行结果构建为可反馈给 LLM 的消息。
 * </p>
 */
public class ToolResultMessageBuilder {

    private final ToolResultConverter converter;

    public ToolResultMessageBuilder() {
        this(new DefaultToolResultConverter());
    }

    public ToolResultMessageBuilder(ToolResultConverter converter) {
        this.converter = converter;
    }

    /**
     * 构建工具结果消息。
     *
     * @param callParam 调用参数
     * @param result    执行结果
     * @return Msg 消息实例
     */
    public Msg build(ToolCallParam callParam, ToolResult result) {
        String content = converter.convert(result);
        return Msg.builder(MsgRole.TOOL)
            .addToolResult(callParam.getCallId(), content, !result.isSuccess())
            .build();
    }
}
