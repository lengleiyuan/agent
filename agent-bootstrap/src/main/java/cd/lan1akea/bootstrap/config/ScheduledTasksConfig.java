package cd.lan1akea.bootstrap.config;

import cd.lan1akea.core.intervention.InterventionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时任务配置。
 * 审批过期清理等周期性维护任务。
 */
@Component
public class ScheduledTasksConfig {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTasksConfig.class);

    private final InterventionStore interventionStore;

    public ScheduledTasksConfig(InterventionStore interventionStore) {
        this.interventionStore = interventionStore;
    }

    /**
     * 每分钟清理过期审批记录。
     */
    @Scheduled(fixedRate = 60_000)
    public void cleanupExpiredApprovals() {
        try {
            interventionStore.cleanupExpired();
        } catch (Exception e) {
            log.warn("审批过期清理失败", e);
        }
    }
}
