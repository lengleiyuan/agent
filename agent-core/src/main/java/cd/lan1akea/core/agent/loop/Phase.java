package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.message.ToolUseBlock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ReAct 循环阶段状态。
 * 不可变，通过静态工厂创建，通过检查方法判断类型。
 */
public final class Phase {

    public enum Type { GUARD, REASON, ACT, OBSERVE }

    private final Type type;
    private final List<ToolUseBlock> toolCalls;

    private Phase(Type type, List<ToolUseBlock> toolCalls) {
        this.type = type;
        this.toolCalls = toolCalls != null
                ? Collections.unmodifiableList(new ArrayList<>(toolCalls))
                : null;
    }

    // ---- 静态工厂 ----

    public static Phase guard() {
        return new Phase(Type.GUARD, null);
    }

    public static Phase reason() {
        return new Phase(Type.REASON, null);
    }

    public static Phase act(List<ToolUseBlock> toolCalls) {
        return new Phase(Type.ACT, toolCalls);
    }

    public static Phase observe() {
        return new Phase(Type.OBSERVE, null);
    }

    // ---- 访问器 ----

    public Type getType() { return type; }
    public List<ToolUseBlock> getToolCalls() { return toolCalls; }

    public boolean isGuard()   { return type == Type.GUARD; }
    public boolean isReason()  { return type == Type.REASON; }
    public boolean isAct()     { return type == Type.ACT; }
    public boolean isObserve() { return type == Type.OBSERVE; }

    @Override
    public String toString() {
        switch (type) {
            case ACT: return "Act[tools=" + (toolCalls != null ? toolCalls.size() : 0) + "]";
            case OBSERVE: return "Observe[]";
            default: return type.name();
        }
    }
}
