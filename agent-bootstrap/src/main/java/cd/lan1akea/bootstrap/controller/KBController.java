package cd.lan1akea.bootstrap.controller;

import cd.lan1akea.harness.hook.KnowledgeBaseHook;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/kb")
public class KBController {

    private static final ConcurrentHashMap<String, String> kbStore = new ConcurrentHashMap<>();

    public static KnowledgeBaseHook createHook() {
        return new KnowledgeBaseHook((query, ctx) -> kbStore.get(query));
    }

    @GetMapping
    public Mono<Map<String, Object>> list() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("count", kbStore.size());
        r.put("entries", Map.copyOf(kbStore));
        return Mono.just(r);
    }

    @PostMapping
    public Mono<Map<String, Object>> put(@RequestBody Map<String, String> body) {
        String q = body.get("question"), a = body.get("answer");
        if (q == null || q.isBlank() || a == null || a.isBlank())
            return Mono.just(Map.of("ok", false, "message", "question 和 answer 不能为空"));
        kbStore.put(q, a);
        return Mono.just(Map.of("ok", true, "message", "已添加", "count", kbStore.size()));
    }

    @DeleteMapping
    public Mono<Map<String, Object>> remove(@RequestParam String question) {
        String removed = kbStore.remove(question);
        return Mono.just(Map.of("ok", true, "removed", removed != null, "count", kbStore.size()));
    }
}
