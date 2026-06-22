package cd.lan1akea.core.middleware;

import cd.lan1akea.core.agent.loop.LoopContext;
import cd.lan1akea.core.model.ChatResponse;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 中间件链。
 * <p>
 * 持有有序的 Middleware 列表，按注册顺序执行。
 * </p>
 */
public class MiddlewareChain {

    private final List<Middleware> middlewares = new ArrayList<>();

    /** 注册中间件 */
    public void register(Middleware middleware) {
        middlewares.add(middleware);
    }

    /** 执行前置中间件链 */
    public Mono<LoopContext> applyBefore(LoopContext ctx) {
        Mono<LoopContext> chain = Mono.just(ctx);
        for (Middleware mw : middlewares) {
            chain = chain.flatMap(mw::before);
        }
        return chain;
    }

    /** 执行后置中间件链 */
    public Mono<ChatResponse> applyAfter(ChatResponse response) {
        Mono<ChatResponse> chain = Mono.just(response);
        for (Middleware mw : middlewares) {
            chain = chain.flatMap(mw::after);
        }
        return chain;
    }

    /** @return 中间件数量 */
    public int size() { return middlewares.size(); }
}
