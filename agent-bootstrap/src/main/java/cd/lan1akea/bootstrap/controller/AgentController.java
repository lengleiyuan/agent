package cd.lan1akea.bootstrap.controller;

import cd.lan1akea.core.context.RuntimeContext;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.UserMessage;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.ChatStreamChunk;
import cd.lan1akea.harness.HarnessAgent;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Agent REST API 控制器。
 * <p>
 * 支持通过请求头传递租户上下文：
 * <ul>
 * <li>{@code X-Tenant-Id} — 租户ID</li>
 * <li>{@code X-User-Id} — 用户ID</li>
 * <li>{@code X-Session-Id} — 会话ID（可选，不传则自动创建）</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final HarnessAgent defaultAgent;

    public AgentController(HarnessAgent defaultAgent) {
        this.defaultAgent = defaultAgent;
    }

    /**
     * 单次对话。
     */
    @PostMapping("/chat")
    public Mono<ChatResponse> chat(@RequestBody Map<String, Object> request,
                                    @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                    @RequestHeader(value = "X-User-Id", required = false) String userId,
                                    @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        String message = (String) request.getOrDefault("message", "");
        if (message.isEmpty()) {
            return Mono.error(new IllegalArgumentException("message不能为空"));
        }
        Msg userMsg = UserMessage.of(message);
        RuntimeContext ctx = RuntimeContext.builder()
            .tenantId(tenantId).userId(userId).sessionId(sessionId).build();
        return defaultAgent.chat(List.of(userMsg), ctx);
    }

    /**
     * 流式对话（SSE）。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatStreamChunk> stream(@RequestBody Map<String, Object> request,
                                         @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                         @RequestHeader(value = "X-User-Id", required = false) String userId,
                                         @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        String message = (String) request.getOrDefault("message", "");
        if (message.isEmpty()) {
            return Flux.error(new IllegalArgumentException("message不能为空"));
        }
        Msg userMsg = UserMessage.of(message);
        RuntimeContext ctx = RuntimeContext.builder()
            .tenantId(tenantId).userId(userId).sessionId(sessionId).build();
        return defaultAgent.stream(List.of(userMsg), ctx);
    }
}
