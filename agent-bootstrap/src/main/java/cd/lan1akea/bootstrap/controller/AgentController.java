package cd.lan1akea.bootstrap.controller;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.UserMessage;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.ChatStreamChunk;
import cd.lan1akea.harness.HarnessAgent;
import cd.lan1akea.harness.HarnessAgentBuilder;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Agent REST API 控制器。
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
    public Mono<ChatResponse> chat(@RequestBody Map<String, Object> request) {
        String message = (String) request.getOrDefault("message", "");
        if (message.isEmpty()) {
            return Mono.error(new IllegalArgumentException("message不能为空"));
        }
        Msg userMsg = UserMessage.of(message);
        return defaultAgent.chat(List.of(userMsg));
    }

    /**
     * 流式对话（SSE）。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatStreamChunk> stream(@RequestBody Map<String, Object> request) {
        String message = (String) request.getOrDefault("message", "");
        if (message.isEmpty()) {
            return Flux.error(new IllegalArgumentException("message不能为空"));
        }
        Msg userMsg = UserMessage.of(message);
        return defaultAgent.stream(List.of(userMsg));
    }
}
