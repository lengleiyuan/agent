package cd.lan1akea.core.shutdown;

import reactor.core.publisher.Mono;

/**
 * 停机钩子接口。
 */
public interface ShutdownHook {

    /** @return 钩子名称 */
    String getName();

    /** 停机时执行 */
    Mono<Void> onShutdown();
}
