package cd.lan1akea.core.state;

import reactor.core.publisher.Mono;

/**
 * 状态存储接口。
 */
public interface StateStore {

    /** 保存状态检查点 */
    Mono<Void> save(AgentState state);

    /** 加载最新状态 */
    Mono<AgentState> loadLatest(String sessionId);

    /** 删除会话状态 */
    Mono<Void> deleteBySession(String sessionId);
}
