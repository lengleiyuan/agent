package cd.lan1akea.bootstrap.controller;

import cd.lan1akea.core.context.RuntimeContext;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.UserMessage;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.ChatStreamChunk;
import cd.lan1akea.core.session.SessionId;
import cd.lan1akea.harness.HarnessAgent;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final HarnessAgent defaultAgent;

    public AgentController(HarnessAgent defaultAgent) {
        this.defaultAgent = defaultAgent;
    }

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

    @GetMapping("/session/{sessionId}/history")
    public Flux<Msg> history(@PathVariable("sessionId") String sessionId,
                              @RequestParam(name = "offset", defaultValue = "0") int offset,
                              @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return defaultAgent.getStateStore().getHistory(new SessionId(sessionId), offset, limit);
    }

    @PostMapping("/interrupt")
    public Mono<Map<String, Object>> interrupt(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        defaultAgent.interrupt();
        Map<String, Object> result = Map.of("ok", true, "message", "已发送中断信号",
            "sessionId", sessionId != null ? sessionId : "");
        return Mono.just(result);
    }

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
