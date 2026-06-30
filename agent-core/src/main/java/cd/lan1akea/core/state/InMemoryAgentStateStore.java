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

    /**
     * 内存会话存储（按会话 ID 索引）
     */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    /**
     * 内存检查点存储（按会话 ID 索引）
     */
    private final Map<String, AgentState> checkpoints = new ConcurrentHashMap<>();


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
    public Flux<Session> listByTenant(String tenantId) {
        return Flux.fromStream(sessions.values().stream()
            .filter(s -> tenantId.equals(s.getTenantId())));
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


    @Override
    public Mono<Void> addTurn(SessionId sessionId, ChatTurn turn) {
        Session session = sessions.computeIfAbsent(sessionId.getValue(),
            k -> new Session(sessionId, "default", "auto", SessionState.ACTIVE, null, null, null));
        session.addTurn(turn);
        return Mono.empty();
    }

    @Override
    public Flux<Msg> getHistory(SessionId sessionId) {
        Session session = sessions.get(sessionId.getValue());
        if (session == null) return Flux.empty();

        java.util.Set<String> seen = new java.util.HashSet<>();
        List<Msg> history = new ArrayList<>();
        for (ChatTurn turn : session.getTurns()) {
            List<Msg> all = turn.getAllMessages();
            if (all != null) {
                addAllDeduped(history, all, seen);
            } else {
                List<Msg> userMsgs = turn.getUserMessages();
                List<Msg> asstMsgs = turn.getAssistantMessages();
                List<Msg> toolMsgs = turn.getToolMessages();

                addAllDeduped(history, userMsgs, seen);
                if (userMsgs.isEmpty() && turn.getUserMsgJson() != null && !turn.getUserMsgJson().isEmpty())
                    history.add(UserMessage.of(turn.getUserMsgJson()));

                addAllDeduped(history, asstMsgs, seen);
                if (asstMsgs.isEmpty() && turn.getAssistantMsgJson() != null && !turn.getAssistantMsgJson().isEmpty())
                    history.add(AssistantMessage.of(turn.getAssistantMsgJson()));

                addAllDeduped(history, toolMsgs, seen);
            }
        }
        return Flux.fromIterable(history);
    }

    private void addAllDeduped(List<Msg> dest, List<Msg> src, java.util.Set<String> seen) {
        for (Msg m : src) {
            if (seen.add(m.getId())) dest.add(m);
        }
    }


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
