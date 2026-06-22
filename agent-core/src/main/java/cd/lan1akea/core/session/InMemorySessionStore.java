package cd.lan1akea.core.session;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存会话存储（测试用）。
 */
public class InMemorySessionStore implements SessionStore {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

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
    public Mono<Void> addTurn(SessionId sessionId, ChatTurn turn) {
        Session session = sessions.get(sessionId.getValue());
        if (session != null) {
            session.addTurn(turn);
        }
        return Mono.empty();
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
        return Mono.empty();
    }
}
