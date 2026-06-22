package cd.lan1akea.core.session;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 会话存储接口。
 * <p>
 * 定义会话持久化的标准操作，支持 MySQL、内存等不同实现。
 * </p>
 */
public interface SessionStore {

    /** 创建会话 */
    Mono<Session> create(Session session);

    /** 按ID查找 */
    Mono<Session> findById(SessionId id);

    /** 按租户列出会话 */
    Flux<Session> listByTenant(long tenantId);

    /** 添加对话轮次 */
    Mono<Void> addTurn(SessionId sessionId, ChatTurn turn);

    /** 更新会话状态 */
    Mono<Void> updateState(SessionId sessionId, SessionState state);

    /** 关闭会话 */
    Mono<Void> close(SessionId sessionId);

    /** 删除会话 */
    Mono<Void> delete(SessionId sessionId);
}
