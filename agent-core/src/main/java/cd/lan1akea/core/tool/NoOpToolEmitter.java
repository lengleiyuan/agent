package cd.lan1akea.core.tool;

/**
 * 空操作工具事件发射器（显式禁用事件）。
 */
public class NoOpToolEmitter implements ToolEmitter {

    @Override
    public void beforeExecute(Tool tool, ToolCallParam params) { }

    @Override
    public void afterExecute(Tool tool, ToolCallParam params, ToolResult result) { }

    @Override
    public void onError(Tool tool, ToolCallParam params, Throwable error) { }
}
