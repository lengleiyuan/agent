package cd.lan1akea.core.hook;

import cd.lan1akea.core.tool.Tool;
import cd.lan1akea.core.tool.ToolCallContext;
import cd.lan1akea.core.tool.ToolResult;

/**
 * 工具调用 Hook 事件。
 * 携带工具实例、调用参数和结果，Hook 可直接从事件获取所需信息，
 * 无需额外注入 ToolRegistry。
 */
public class ToolCallEvent extends HookEvent {

    /**
     * 工具实例（PRE_TOOL_CALL 时由框架自动注入）
     */
    private Tool tool;
    /**
     * 工具调用参数
     */
    private ToolCallContext callParam;
    /**
     * 工具调用结果（POST_TOOL_CALL 时有效）
     */
    private ToolResult result;

    /**
     * 创建指定事件类型的工具调用事件。
     */
    public ToolCallEvent(HookEventType eventType) {
        super(eventType);
    }

    /**
     * 创建带调用参数的工具调用事件。
     */
    public ToolCallEvent(HookEventType eventType, ToolCallContext callParam) {
        super(eventType);
        this.callParam = callParam;
    }

    /**
     * 创建带调用参数和结果的工具调用事件。
     */
    public ToolCallEvent(HookEventType eventType, ToolCallContext callParam, ToolResult result) {
        super(eventType);
        this.callParam = callParam;
        this.result = result;
    }

    /**
     * @return 工具实例（PRE_TOOL_CALL 时由框架设置，POST_TOOL_CALL 时保持可用）
     */
    public Tool getTool() { return tool; }
    /**
     * 设置工具实例。
     */
    public void setTool(Tool tool) { this.tool = tool; }

    /**
     * @return 工具调用参数
     */
    public ToolCallContext getCallParam() { return callParam; }
    /**
     * 设置工具调用参数。
     */
    public void setCallParam(ToolCallContext callParam) { this.callParam = callParam; }

    /**
     * @return 工具调用结果（POST_TOOL_CALL 时有效）
     */
    public ToolResult getResult() { return result; }
    /**
     * 设置工具调用结果。
     */
    public void setResult(ToolResult result) { this.result = result; }
}
