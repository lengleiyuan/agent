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

    /**
     * 循环阶段类型枚举。
     */
    public enum Type { GUARD, REASON, ACT, OBSERVE }

    /** 当前阶段类型。 */
    private final Type type;
    /** ACT 阶段待执行的工具调用列表，非 ACT 阶段为 null。 */
    private final List<ToolUseBlock> toolCalls;

    private Phase(Type type, List<ToolUseBlock> toolCalls) {
        this.type = type;
        this.toolCalls = toolCalls != null
                ? Collections.unmodifiableList(new ArrayList<>(toolCalls))
                : null;
    }

    // ---- 静态工厂 ----

    /**
     * 创建 GUARD 阶段实例。
     *
     * @return GUARD 阶段
     */
    public static Phase guard() {
        return new Phase(Type.GUARD, null);
    }

    /**
     * 创建 REASON 阶段实例。
     *
     * @return REASON 阶段
     */
    public static Phase reason() {
        return new Phase(Type.REASON, null);
    }

    /**
     * 创建 ACT 阶段实例。
     *
     * @param toolCalls 工具调用列表
     * @return ACT 阶段
     */
    public static Phase act(List<ToolUseBlock> toolCalls) {
        return new Phase(Type.ACT, toolCalls);
    }

    /**
     * 创建 OBSERVE 阶段实例。
     *
     * @return OBSERVE 阶段
     */
    public static Phase observe() {
        return new Phase(Type.OBSERVE, null);
    }

    // ---- 访问器 ----

    /**
     * 返回当前阶段类型。
     *
     * @return 阶段类型
     */
    public Type getType() { return type; }

    /**
     * 返回工具调用列表。
     *
     * @return 不可变的工具调用列表，非 ACT 阶段返回 null
     */
    public List<ToolUseBlock> getToolCalls() { return toolCalls; }

    /**
     * 判断当前是否为 GUARD 阶段。
     *
     * @return true 如果是 GUARD 阶段
     */
    public boolean isGuard()   { return type == Type.GUARD; }

    /**
     * 判断当前是否为 REASON 阶段。
     *
     * @return true 如果是 REASON 阶段
     */
    public boolean isReason()  { return type == Type.REASON; }

    /**
     * 判断当前是否为 ACT 阶段。
     *
     * @return true 如果是 ACT 阶段
     */
    public boolean isAct()     { return type == Type.ACT; }

    /**
     * 判断当前是否为 OBSERVE 阶段。
     *
     * @return true 如果是 OBSERVE 阶段
     */
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
