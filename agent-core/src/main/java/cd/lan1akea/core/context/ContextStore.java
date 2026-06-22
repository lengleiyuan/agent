package cd.lan1akea.core.context;

import reactor.core.publisher.Mono;

/**
 * 上下文存储接口。
 */
public interface ContextStore {

    /** 保存上下文 */
    Mono<Void> save(String key, RuntimeContext context);

    /** 加载上下文 */
    Mono<RuntimeContext> load(String key);

    /** 删除上下文 */
    Mono<Void> remove(String key);
}
