package cd.lan1akea.core.hook;

/**
 * Hook 处理结果。
 * <p>
 * 决定 Hook 链在事件处理后的行为走向。
 * </p>
 */
public class HookResult {

    /** 处理结果类型 */
    private final ResultType resultType;

    /** 可选的修改后数据（MODIFY 时有效） */
    private final Object modifiedData;

    /** 可选的中断原因（INTERRUPT 时有效） */
    private final String interruptReason;

    /** 可选的终止原因（ABORT 时有效） */
    private final String abortReason;

    private HookResult(ResultType resultType, Object modifiedData,
                       String interruptReason, String abortReason) {
        this.resultType = resultType;
        this.modifiedData = modifiedData;
        this.interruptReason = interruptReason;
        this.abortReason = abortReason;
    }

    // === 工厂方法 ===

    /** 放行，继续执行 */
    public static HookResult continue_() {
        return new HookResult(ResultType.CONTINUE, null, null, null);
    }

    /** 修改数据后继续（如修改输入参数、修正输出） */
    public static HookResult modify(Object modifiedData) {
        return new HookResult(ResultType.MODIFY, modifiedData, null, null);
    }

    /** 暂停，等待人工干预 */
    public static HookResult interrupt(String reason) {
        return new HookResult(ResultType.INTERRUPT, null, reason, null);
    }

    /** 终止执行 */
    public static HookResult abort(String reason) {
        return new HookResult(ResultType.ABORT, null, null, reason);
    }

    // === Getters ===

    public ResultType getResultType() { return resultType; }
    public Object getModifiedData() { return modifiedData; }
    public String getInterruptReason() { return interruptReason; }
    public String getAbortReason() { return abortReason; }

    public boolean isContinue() { return resultType == ResultType.CONTINUE; }
    public boolean isModify() { return resultType == ResultType.MODIFY; }
    public boolean isInterrupt() { return resultType == ResultType.INTERRUPT; }
    public boolean isAbort() { return resultType == ResultType.ABORT; }

    /**
     * 结果类型枚举。
     */
    public enum ResultType {
        /** 放行 */
        CONTINUE,
        /** 修改数据 */
        MODIFY,
        /** 中断等待人工 */
        INTERRUPT,
        /** 终止 */
        ABORT
    }
}
