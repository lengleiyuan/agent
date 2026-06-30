package cd.lan1akea.harness.store;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.session.ChatTurn;
import cd.lan1akea.core.session.Session;
import cd.lan1akea.core.session.SessionId;
import cd.lan1akea.core.session.SessionState;
import cd.lan1akea.core.state.AgentState;
import cd.lan1akea.core.state.AgentStateStore;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Agent 状态存储接口（门面层，可选注入）。
 * public class RedisAgentStateStore implements IAgentStateStore {
 *     public Mono<Session> findById(SessionId id) { ... }
 *     ...
 * }
 *
 * HarnessAgent.builder()
 *     .stateStore(new RedisAgentStateStore())
 *     .build();
 * }</pre>
 */
public interface IAgentStateStore extends AgentStateStore {

    @Override Mono<Session> create(Session session);

    @Override Mono<Session> findById(SessionId id);

    @Override Flux<Session> listByTenant(String tenantId);

    /** 更新会话状态（ACTIVE → PAUSED → CLOSED） */
    @Override Mono<Void> updateState(SessionId sessionId, SessionState state);

    /** 关闭会话 */
    @Override Mono<Void> close(SessionId sessionId);

    /** 删除会话及其所有数据（轮次 + 检查点） */
    @Override Mono<Void> delete(SessionId sessionId);

    /**
     * 添加对话轮次。每条 ChatTurn 包含结构化消息列表（含 ContentBlock），
     * 持久化时保留完整结构，恢复时还原全部内容。
     */
    @Override Mono<Void> addTurn(SessionId sessionId, ChatTurn turn);

    /** 获取会话历史消息，按轮次顺序还原，保留所有 ContentBlock */
    @Override Flux<Msg> getHistory(SessionId sessionId);

    /**
     * 保存执行检查点。每次 ReAct 迭代后调用，保存当前消息列表、迭代计数、工具状态。
     * 用于进程重启后恢复执行。
     */
    @Override Mono<Void> saveCheckpoint(AgentState state);

    /**
     * 加载最新检查点。
     * @param sessionId 会话 ID
     * @return 最新的 AgentState，无检查点返回 empty
     */
    @Override Mono<AgentState> loadLatestCheckpoint(String sessionId);

    /** 删除会话的所有检查点 */
    @Override Mono<Void> deleteCheckpoints(String sessionId);
}
