package cd.lan1akea.core.hook;

/**
 * Hook 处理结果。
 * 决定 Hook 链在事件处理后的行为走向。
 */
public class HookResult {

    /**
     * 处理结果类型
     */
    private final ResultType resultType;

    /**
     * 可选的修改后数据（MODIFY 时有效）
     */
    private final Object modifiedData;

    /**
     * 可选的中断原因（INTERRUPT 时有效）
     */
    private final String interruptReason;

    /**
     * 可选的终止原因（ABORT 时有效）
     */
    private final String abortReason;

    /**
     * 可选的跳过原因（SKIP 时有效）
     */
    private final String skipReason;

    /**
     * 私有构造器。
     */
    private HookResult(ResultType resultType, Object modifiedData,
                       String interruptReason, String abortReason, String skipReason) {
        this.resultType = resultType;
        this.modifiedData = modifiedData;
        this.interruptReason = interruptReason;
        this.abortReason = abortReason;
        this.skipReason = skipReason;
    }


    /**
     * 放行，继续执行
     */
    public static HookResult continue_() {
        return new HookResult(ResultType.CONTINUE, null, null, null, null);
    }

    /**
     * 修改数据后继续（如修改输入参数、修正输出）
     */
    public static HookResult modify(Object modifiedData) {
        return new HookResult(ResultType.MODIFY, modifiedData, null, null, null);
    }

    /**
     * 暂停，等待人工干预
     */
    public static HookResult interrupt(String reason) {
        return new HookResult(ResultType.INTERRUPT, null, reason, null, null);
    }

    /**
     * 终止执行
     */
    public static HookResult abort(String reason) {
        return new HookResult(ResultType.ABORT, null, null, reason, null);
    }

    /**
     * 跳过当前工具，返回空结果继续推理（鉴权等场景）
     */
    public static HookResult skip(String reason) {
        return new HookResult(ResultType.SKIP, null, null, null, reason);
    }


    /**
     * @return 结果类型
     */
    public ResultType getResultType() { return resultType; }
    /**
     * @return 修改后的数据（MODIFY 时有效）
     */
    public Object getModifiedData() { return modifiedData; }
    /**
     * @return 中断原因（INTERRUPT 时有效）
     */
    public String getInterruptReason() { return interruptReason; }
    /**
     * @return 终止原因（ABORT 时有效）
     */
    public String getAbortReason() { return abortReason; }
    /**
     * @return 跳过原因（SKIP 时有效）
     */
    public String getSkipReason() { return skipReason; }

    /**
     * @return 是否继续
     */
    public boolean isContinue() { return resultType == ResultType.CONTINUE; }
    /**
     * @return 是否修改
     */
    public boolean isModify() { return resultType == ResultType.MODIFY; }
    /**
     * @return 是否中断
     */
    public boolean isInterrupt() { return resultType == ResultType.INTERRUPT; }
    /**
     * @return 是否终止
     */
    public boolean isAbort() { return resultType == ResultType.ABORT; }
    /**
     * @return 是否跳过
     */
    public boolean isSkip() { return resultType == ResultType.SKIP; }

    /**
     * 结果类型枚举。
     */
    public enum ResultType {
        /**
         * 放行
         */
        CONTINUE,
        /**
         * 修改数据
         */
        MODIFY,
        /**
         * 中断等待人工
         */
        INTERRUPT,
        /**
         * 终止
         */
        ABORT,
        /**
         * 跳过当前操作，返回空成功结果继续
         */
        SKIP
    }
}
