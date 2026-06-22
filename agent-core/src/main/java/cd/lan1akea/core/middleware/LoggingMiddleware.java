package cd.lan1akea.core.middleware;

import cd.lan1akea.core.agent.loop.LoopContext;
import cd.lan1akea.core.model.ChatResponse;
import reactor.core.publisher.Mono;

/**
 * 日志中间件。
 * <p>
 * 记录请求和响应的基本信息。
 * </p>
 */
public class LoggingMiddleware implements Middleware {

    @Override
    public Mono<LoopContext> before(LoopContext ctx) {
        String msg = ctx.getMessages().isEmpty() ? "" :
            ctx.getMessages().get(ctx.getMessages().size() - 1).getTextContent();
        System.out.printf("[Agent] 请求 | agent=%s | 迭代=%d/%d | 最后消息=%s%n",
            ctx.getAgentName(), ctx.getIteration(), ctx.getMaxIterations(),
            msg.length() > 50 ? msg.substring(0, 50) + "..." : msg);
        return Mono.just(ctx);
    }

    @Override
    public Mono<ChatResponse> after(ChatResponse response) {
        System.out.printf("[Agent] 响应 | 模型=%s | token=%s | finishReason=%s%n",
            response.getModelName(), response.getUsage(), response.getFinishReason());
        return Mono.just(response);
    }
}
