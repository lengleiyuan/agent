package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.model.ChatResponse;

/**
 * 状态机决策结果。
 * 不可变，通过静态工厂创建。
 */
public final class Decision {

    /** 是否终止循环。 */
    private final boolean stop;
    /** 下一循环阶段（stop 为 true 时为 null）。 */
    private final Phase nextPhase;
    /** 最终响应结果（stop 为 true 时有效）。 */
    private final ChatResponse response;

    private Decision(boolean stop, Phase nextPhase, ChatResponse response) {
        this.stop = stop;
        this.nextPhase = nextPhase;
        this.response = response;
    }

    /** 继续循环，进入下一阶段 */
    public static Decision continue_(Phase next) {
        return new Decision(false, next, null);
    }

    /** 终止循环，返回最终响应 */
    public static Decision stop(ChatResponse resp) {
        return new Decision(true, null, resp);
    }

    /**
     * 返回是否终止循环。
     *
     * @return true 表示终止循环
     */
    public boolean isStop()          { return stop; }

    /**
     * 返回下一循环阶段。
     *
     * @return 下一阶段，stop 为 true 时为 null
     */
    public Phase getNextPhase()      { return nextPhase; }

    /**
     * 返回最终响应结果。
     *
     * @return 最终响应，stop 为 true 时有效
     */
    public ChatResponse getResponse() { return response; }

    @Override
    public String toString() {
        return stop ? "Stop" : "Continue(" + nextPhase + ")";
    }
}
