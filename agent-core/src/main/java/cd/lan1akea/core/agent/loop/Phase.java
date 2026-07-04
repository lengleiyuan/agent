package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.message.ToolUseBlock;
import cd.lan1akea.core.tool.ToolResult;

import java.util.List;

/**
 * ReAct 循环阶段状态。
 * 不可变，通过静态工厂创建，通过检查方法判断类型。
 */
public final class Phase {

    public enum Type { GUARD, REASON, ACT, OBSERVE }

    private final Type type;
    private final List<ToolUseBlock> toolCalls;
    private final List<ToolResult> results;

    private Phase(Type type, List<ToolUseBlock> toolCalls, List<ToolResult> results) {
        this.type = type;
        this.toolCalls = toolCalls;
        this.results = results;
    }

    // ---- 静态工厂 ----

    public static Phase guard() {
        return new Phase(Type.GUARD, null, null);
    }

    public static Phase reason() {
        return new Phase(Type.REASON, null, null);
    }

    public static Phase act(List<ToolUseBlock> toolCalls) {
        return new Phase(Type.ACT, toolCalls, null);
    }

    public static Phase observe(List<ToolResult> results) {
        return new Phase(Type.OBSERVE, null, results);
    }

    // ---- 访问器 ----

    public Type type() { return type; }
    public List<ToolUseBlock> toolCalls() { return toolCalls; }
    public List<ToolResult> results() { return results; }

    public boolean isGuard()   { return type == Type.GUARD; }
    public boolean isReason()  { return type == Type.REASON; }
    public boolean isAct()     { return type == Type.ACT; }
    public boolean isObserve() { return type == Type.OBSERVE; }

    @Override
    public String toString() {
        switch (type) {
            case ACT: return "Act[tools=" + (toolCalls != null ? toolCalls.size() : 0) + "]";
            case OBSERVE: return "Observe[results=" + (results != null ? results.size() : 0) + "]";
            default: return type.name();
        }
    }
}
