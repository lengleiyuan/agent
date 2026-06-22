package cd.lan1akea.core.tool;

/**
 * 工具事件发射器接口。
 * <p>
 * 在工具执行前后发射事件，供 Hook 系统感知。
 * </p>
 */
public interface ToolEmitter {

    /** 工具执行前 */
    void beforeExecute(Tool tool, ToolCallParam params);

    /** 工具执行后 */
    void afterExecute(Tool tool, ToolCallParam params, ToolResult result);

    /** 工具执行出错 */
    void onError(Tool tool, ToolCallParam params, Throwable error);
}
