package cd.lan1akea.harness.spi;

import cd.lan1akea.core.session.SessionStore;
import cd.lan1akea.core.memory.MemoryStore;
import cd.lan1akea.core.state.StateStore;

/**
 * 存储提供商 SPI。
 * <p>
 * 第三方实现此接口来提供自定义存储后端（如 Redis、PostgreSQL 等）。
 * </p>
 */
public interface StorageProviderSpi {

    /** @return 会话存储实现 */
    SessionStore createSessionStore();

    /** @return 记忆存储实现 */
    MemoryStore createMemoryStore();

    /** @return 状态存储实现 */
    StateStore createStateStore();

    default String getProviderName() {
        return getClass().getSimpleName();
    }
}
