package cd.lan1akea.core.state;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.util.JsonUtils;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 检查点服务。
 * <p>
 * 负责创建和恢复 Agent 执行检查点。
 * </p>
 */
public class CheckpointService {

    private final StateStore stateStore;

    public CheckpointService(StateStore stateStore) {
        this.stateStore = stateStore;
    }

    /**
     * 保存检查点。
     *
     * @param agentName Agent 名称
     * @param sessionId 会话ID
     * @param iteration 当前迭代
     * @param messages  消息历史
     * @param toolState 工具状态
     * @return Mono&lt;Void&gt;
     */
    public Mono<Void> saveCheckpoint(String agentName, String sessionId,
                                      int iteration, List<Msg> messages, Object toolState) {
        AgentState state = new AgentState(
            agentName, sessionId, iteration,
            JsonUtils.toCompactJson(messages),
            JsonUtils.toCompactJson(toolState),
            System.currentTimeMillis());
        return stateStore.save(state);
    }

    /**
     * 恢复最新检查点。
     *
     * @param sessionId 会话ID
     * @return Mono&lt;AgentState&gt;
     */
    public Mono<AgentState> restoreCheckpoint(String sessionId) {
        return stateStore.loadLatest(sessionId);
    }
}
