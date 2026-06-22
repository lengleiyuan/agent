package cd.lan1akea.harness.spi;

import cd.lan1akea.core.hook.Hook;

import java.util.List;

/**
 * Hook 提供商 SPI。
 * <p>
 * 第三方实现此接口来批量注册自定义 Hook。
 * </p>
 */
public interface HookProviderSpi {

    /**
     * @return 提供的 Hook 列表
     */
    List<Hook> provideHooks();

    default String getProviderName() {
        return getClass().getSimpleName();
    }
}
