package cd.lan1akea.core.hook;

import cd.lan1akea.core.tool.ToolCallParam;
import cd.lan1akea.core.tool.ToolResult;

/**
 * 工具调用 Hook 事件。
 * <p>
 * 携带工具调用参数和结果，使 Hook 可以检查和修改工具调用行为。
 * </p>
 */
public class ToolCallEvent extends HookEvent {

    /** 工具调用参数 */
    private ToolCallParam callParam;
    /** 工具调用结果（POST_TOOL_CALL 时有效） */
    private ToolResult result;

    public ToolCallEvent(HookEventType eventType) {
        super(eventType);
    }

    public ToolCallEvent(HookEventType eventType, ToolCallParam callParam) {
        super(eventType);
        this.callParam = callParam;
    }

    public ToolCallEvent(HookEventType eventType, ToolCallParam callParam, ToolResult result) {
        super(eventType);
        this.callParam = callParam;
        this.result = result;
    }

    public ToolCallParam getCallParam() { return callParam; }
    public void setCallParam(ToolCallParam callParam) { this.callParam = callParam; }

    public ToolResult getResult() { return result; }
    public void setResult(ToolResult result) { this.result = result; }
}
