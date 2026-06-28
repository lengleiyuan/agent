package cd.lan1akea.bootstrap.controller;

import cd.lan1akea.core.hook.impl.KnowledgeBaseHook;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/kb")
public class KBController {

    // 与 DevAgentConfig 中注入的 KnowledgeBaseHook 共享同一个 store
    private static final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

    /** 注册 KB 单例供 DevAgentConfig 使用 */
    public static KnowledgeBaseHook createHook() {
        return new KnowledgeBaseHook(store::get);
    }

    @GetMapping
    public Mono<Map<String, Object>> list() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("count", store.size());
        r.put("entries", Map.copyOf(store));
        return Mono.just(r);
    }

    @PostMapping
    public Mono<Map<String, Object>> put(@RequestBody Map<String, String> body) {
        String q = body.get("question"), a = body.get("answer");
        if (q == null || q.isBlank() || a == null || a.isBlank())
            return Mono.just(Map.of("ok", false, "message", "question 和 answer 不能为空"));
        store.put(q, a);
        return Mono.just(Map.of("ok", true, "message", "已添加", "count", store.size()));
    }

    @DeleteMapping
    public Mono<Map<String, Object>> remove(@RequestParam String question) {
        String removed = store.remove(question);
        return Mono.just(Map.of("ok", true, "removed", removed != null, "count", store.size()));
    }
}
