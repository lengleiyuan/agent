package cd.lan1akea.core.middleware;

import cd.lan1akea.core.agent.loop.LoopContext;
import reactor.core.publisher.Mono;

/**
 * 中间件接口。
 * <p>
 * 在 Agent 核心调用前后执行，形成管道。
 * 可用于日志记录、限流、重试等横切关注点。
 * </p>
 */
public interface Middleware {

    /**
     * 核心调用前执行。
     *
     * @param ctx 循环上下文
     * @return Mono&lt;LoopContext&gt; 可能被修改的上下文
     */
    default Mono<LoopContext> before(LoopContext ctx) {
        return Mono.just(ctx);
    }

    /**
     * 核心调用后执行。
     *
     * @param response 聊天响应
     * @return Mono&lt;LoopContext&gt; 可能被修改的响应
     */
    default Mono<cd.lan1akea.core.model.ChatResponse> after(cd.lan1akea.core.model.ChatResponse response) {
        return Mono.just(response);
    }
}
