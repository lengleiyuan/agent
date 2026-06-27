package cd.lan1akea.core.tool;

/**
 * 默认工具事件发射器（无操作实现）。
 * 可通过继承覆写特定方法来添加自定义行为。
 */
public class DefaultToolEmitter implements ToolEmitter {

    @Override
    public void beforeExecute(Tool tool, ToolCallContext params) {
        // 默认无操作
    }

    @Override
    public void afterExecute(Tool tool, ToolCallContext params, ToolResult result) {
        // 默认无操作
    }

    @Override
    public void onError(Tool tool, ToolCallContext params, Throwable error) {
        // 默认无操作
    }
}
