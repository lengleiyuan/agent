package cd.lan1akea.bootstrap.config;

import cd.lan1akea.core.tool.mcp.McpClient;
import cd.lan1akea.harness.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP 连接健康检查定时任务。
 * 定期 ping 所有 MCP Server，断开自动重连。
 */
@Component
public class McpHealthCheckConfig {

    private static final Logger log = LoggerFactory.getLogger(McpHealthCheckConfig.class);

    private final HarnessAgent agent;

    public McpHealthCheckConfig(HarnessAgent agent) {
        this.agent = agent;
    }

    /**
     * 每 30 秒检查一次 MCP 连接健康状态。
     */
    @Scheduled(fixedRate = 30_000)
    public void checkMcpConnections() {
        List<McpClient> clients = agent.getMcpClients();
        if (clients.isEmpty()) return;

        for (McpClient client : clients) {
            client.healthCheck()
                .filter(healthy -> !healthy)
                .flatMap(__ -> {
                    log.warn("MCP 连接断开，尝试重连...");
                    return client.reconnect()
                        .doOnSuccess(v -> log.info("MCP 重连成功"))
                        .doOnError(e -> log.warn("MCP 重连失败: {}", e.getMessage()));
                })
                .subscribe();
        }
    }
}
