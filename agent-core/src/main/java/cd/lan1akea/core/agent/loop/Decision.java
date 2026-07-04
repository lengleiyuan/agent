package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.model.ChatResponse;

/**
 * 状态机决策结果。
 * 不可变，通过静态工厂创建。
 */
public final class Decision {

    private final boolean stop;
    private final Phase nextPhase;
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

    public boolean isStop()          { return stop; }
    public Phase getNextPhase()      { return nextPhase; }
    public ChatResponse getResponse() { return response; }

    @Override
    public String toString() {
        return stop ? "Stop" : "Continue(" + nextPhase + ")";
    }
}
