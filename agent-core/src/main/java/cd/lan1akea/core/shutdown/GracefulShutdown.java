package cd.lan1akea.core.shutdown;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 优雅停机协调器。
 * <p>
 * 在 JVM 关闭前依次执行所有注册的 ShutdownHook，确保状态保存和资源释放。
 * </p>
 */
public class GracefulShutdown {

    private final List<ShutdownHook> hooks = new CopyOnWriteArrayList<>();
    private final Duration timeout;

    public GracefulShutdown(Duration timeout) {
        this.timeout = timeout;
    }

    /**
     * 注册停机钩子。
     */
    public void register(ShutdownHook hook) {
        hooks.add(hook);
    }

    /**
     * 执行所有停机钩子。
     *
     * @return Mono&lt;Void&gt;
     */
    public Mono<Void> shutdown() {
        return Flux.fromIterable(hooks)
            .flatMap(hook -> hook.onShutdown()
                .timeout(timeout)
                .onErrorResume(e -> {
                    System.err.println("[GracefulShutdown] Hook 执行失败: "
                        + hook.getName() + " - " + e.getMessage());
                    return Mono.empty();
                }))
            .then();
    }

    /** @return 注册的钩子数量 */
    public int getHookCount() { return hooks.size(); }
}
