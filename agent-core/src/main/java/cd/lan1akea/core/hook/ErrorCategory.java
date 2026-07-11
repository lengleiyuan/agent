package cd.lan1akea.core.hook;

/**
 * 错误分类枚举。
 * 由业务方 ON_ERROR Hook 设定，决定错误的最终走向。
 *
 * <p>用法：</p>
 * <pre>{@code
 * hookChain.register(new Hook() {
 *     public Mono<HookResult> onEvent(HookEvent event, HookContext ctx) {
 *         if (event.getHookEventType() == HookEventType.ON_ERROR && isNetworkTimeout(event.getError())) {
 *             event.setPayload("category", ErrorCategory.NEEDS_HUMAN);  // 触发中断等待人工
 *         }
 *         return Mono.just(HookResult.continue_());
 *     }
 * });
 * }</pre>
 */
public enum ErrorCategory {
    /**
     * 不可恢复，直接终止。
     * 默认值，无 ON_ERROR Hook 时走此路径。
     */
    FATAL,
    /**
     * 需要人工介入。
     * handleError 触发 HookEvent.interrupt(ERROR_HANDOFF, ...) → 暂停循环 → 等待人工反馈后续跑。
     */
    NEEDS_HUMAN
}
