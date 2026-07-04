package cd.lan1akea.bootstrap.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 指标监控 REST API。
 * 提供 Agent 运行时状态和 JVM 系统指标的快照。
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    @GetMapping("/system")
    public Mono<Map<String, Object>> systemMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();

        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        metrics.put("availableProcessors", os.getAvailableProcessors());
        metrics.put("systemLoadAverage", String.format("%.2f", os.getSystemLoadAverage()));

        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        long heapUsed = mem.getHeapMemoryUsage().getUsed() / 1024 / 1024;
        long heapMax = mem.getHeapMemoryUsage().getMax() / 1024 / 1024;
        metrics.put("heapUsedMB", heapUsed);
        metrics.put("heapMaxMB", heapMax);
        metrics.put("heapUsagePercent", heapMax > 0 ? (heapUsed * 100 / heapMax) : 0);

        Runtime rt = Runtime.getRuntime();
        metrics.put("threadCount", Thread.activeCount());

        return Mono.just(metrics);
    }
}
