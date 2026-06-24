package cd.lan1akea.core.state;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.UserMessage;
import cd.lan1akea.core.message.AssistantMessage;
import cd.lan1akea.core.session.ChatTurn;
import cd.lan1akea.core.session.Session;
import cd.lan1akea.core.session.SessionId;
import cd.lan1akea.core.session.SessionState;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存状态存储（测试用）。
 */
public class InMemoryAgentStateStore implements AgentStateStore {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, AgentState> checkpoints = new ConcurrentHashMap<>();

    // ========================================================================
    // 会话生命周期
    // ========================================================================

    @Override
    public Mono<Session> create(Session session) {
        sessions.put(session.getId().getValue(), session);
        return Mono.just(session);
    }

    @Override
    public Mono<Session> findById(SessionId id) {
        Session session = sessions.get(id.getValue());
        return session != null ? Mono.just(session) : Mono.empty();
    }

    @Override
    public Flux<Session> listByTenant(long tenantId) {
        return Flux.fromStream(sessions.values().stream()
            .filter(s -> s.getTenantId() == tenantId));
    }

    @Override
    public Mono<Void> updateState(SessionId sessionId, SessionState state) {
        Session session = sessions.get(sessionId.getValue());
        if (session != null) {
            switch (state) {
                case PAUSED: session.pause(); break;
                case CLOSED: session.close(); break;
                default: break;
            }
        }
        return Mono.empty();
    }

    @Override
    public Mono<Void> close(SessionId sessionId) {
        return updateState(sessionId, SessionState.CLOSED);
    }

    @Override
    public Mono<Void> delete(SessionId sessionId) {
        sessions.remove(sessionId.getValue());
        checkpoints.remove(sessionId.getValue());
        return Mono.empty();
    }

    // ========================================================================
    // 对话持久化
    // ========================================================================

    @Override
    public Mono<Void> addTurn(SessionId sessionId, ChatTurn turn) {
        Session session = sessions.get(sessionId.getValue());
        if (session != null) session.addTurn(turn);
        return Mono.empty();
    }

    @Override
    public Flux<Msg> getHistory(SessionId sessionId) {
        Session session = sessions.get(sessionId.getValue());
        if (session == null) return Flux.empty();

        List<Msg> history = new ArrayList<>();
        for (ChatTurn turn : session.getTurns()) {
            // 优先使用结构化消息，fallback 到 JSON 字符串
            List<Msg> userMsgs = turn.getUserMessages();
            List<Msg> asstMsgs = turn.getAssistantMessages();
            List<Msg> toolMsgs = turn.getToolMessages();

            if (!userMsgs.isEmpty()) history.addAll(userMsgs);
            else if (turn.getUserMsgJson() != null && !turn.getUserMsgJson().isEmpty())
                history.add(UserMessage.of(turn.getUserMsgJson()));

            if (!asstMsgs.isEmpty()) history.addAll(asstMsgs);
            else if (turn.getAssistantMsgJson() != null && !turn.getAssistantMsgJson().isEmpty())
                history.add(AssistantMessage.of(turn.getAssistantMsgJson()));

            if (!toolMsgs.isEmpty()) history.addAll(toolMsgs);
        }
        return Flux.fromIterable(history);
    }

    // ========================================================================
    // 检查点
    // ========================================================================

    @Override
    public Mono<Void> saveCheckpoint(AgentState state) {
        checkpoints.put(state.getSessionId(), state);
        return Mono.empty();
    }

    @Override
    public Mono<AgentState> loadLatestCheckpoint(String sessionId) {
        AgentState state = checkpoints.get(sessionId);
        return state != null ? Mono.just(state) : Mono.empty();
    }

    @Override
    public Mono<Void> deleteCheckpoints(String sessionId) {
        checkpoints.remove(sessionId);
        return Mono.empty();
    }
}
