package cd.lan1akea.core.state;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.session.ChatTurn;
import cd.lan1akea.core.session.Session;
import cd.lan1akea.core.session.SessionId;
import cd.lan1akea.core.session.SessionState;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 统一 Agent 状态存储接口。
 *
 * 合并会话持久化（Session CRUD + 对话轮次）和执行检查点（ReAct 快照）。
 *
 * 会话：多轮对话的容器，按租户隔离
 * 对话轮次：每次用户-助手交换的详细记录
 * 检查点：Agent 执行快照，用于暂停/恢复和崩溃恢复
 *
 * 业务方实现此接口并显式传入（如 MySQL、Redis、JSON File 等）。
 */
public interface AgentStateStore {

    /**
     * 创建会话
     */
    Mono<Session> create(Session session);

    /**
     * 按 ID 查找会话
     */
    Mono<Session> findById(SessionId id);

    /**
     * 按租户列出所有会话
     */
    Flux<Session> listByTenant(String tenantId);

    /**
     * 更新会话状态（ACTIVE -> PAUSED -> CLOSED）
     */
    Mono<Void> updateState(SessionId sessionId, SessionState state);

    /**
     * 关闭会话（等同于 updateState(CLOSED)）
     */
    Mono<Void> close(SessionId sessionId);

    /**
     * 删除会话及其所有数据（轮次 + 检查点）
     */
    Mono<Void> delete(SessionId sessionId);

    /**
     * 添加对话轮次。
     * 每条 ChatTurn 包含结构化消息列表（含 ContentBlock），
     * 持久化时保留完整结构，恢复时还原全部内容。
     */
    Mono<Void> addTurn(SessionId sessionId, ChatTurn turn);

    /**
     * 获取会话历史消息。
     * 按轮次顺序还原，保留所有 ContentBlock（工具调用、图片、思考等）。
     */
    Flux<Msg> getHistory(SessionId sessionId);


    /**
     * 保存执行检查点。
     * 每次 ReAct 迭代后调用，保存当前消息列表、迭代计数、工具状态等。
     * 用于进程重启后恢复执行。
     */
    Mono<Void> saveCheckpoint(AgentState state);

    /**
     * 加载最新检查点。
     *
     * @param sessionId 会话 ID
     * @return 最新的 AgentState，无检查点返回 empty
     */
    Mono<AgentState> loadLatestCheckpoint(String sessionId);

    /**
     * 删除会话的所有检查点。
     */
    Mono<Void> deleteCheckpoints(String sessionId);
}
