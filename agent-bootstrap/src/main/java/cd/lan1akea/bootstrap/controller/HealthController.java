package cd.lan1akea.bootstrap.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 健康检查控制器。
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Mono<Map<String, Object>> health() {
        return Mono.just(Map.of(
            "status", "UP",
            "timestamp", System.currentTimeMillis()
        ));
    }
}
