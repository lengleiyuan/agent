package cd.lan1akea.bootstrap.controller;

import cd.lan1akea.core.model.Cl100kTokenEstimator;
import cd.lan1akea.core.model.TokenEstimator;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Token 精确计数 REST API。
 * 使用 cl100k_base 编码对齐 OpenAI tiktoken。
 */
@RestController
@RequestMapping("/api/token")
public class TokenController {

    private final Cl100kTokenEstimator estimator = new Cl100kTokenEstimator();

    /**
     * 计算文本的 Token 数。
     *
     * @param body 包含 text 字段的请求体
     * @return token 数和文本
     */
    @PostMapping("/count")
    public Mono<Map<String, Object>> count(@RequestBody Map<String, Object> body) {
        String text = (String) body.getOrDefault("text", "");
        if (text.isEmpty()) {
            return Mono.just(Map.of("tokens", 0, "characters", 0, "text", ""));
        }

        int tokens = estimator.count(text);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tokens", tokens);
        result.put("characters", text.length());
        result.put("text", text.length() > 200 ? text.substring(0, 200) + "..." : text);
        return Mono.just(result);
    }

    /**
     * GET 方式快速计数。
     */
    @GetMapping("/count")
    public Mono<Map<String, Object>> countGet(@RequestParam(defaultValue = "") String text) {
        return count(Map.of("text", text));
    }
}
