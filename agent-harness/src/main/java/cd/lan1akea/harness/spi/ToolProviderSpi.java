package cd.lan1akea.harness.spi;

import cd.lan1akea.core.tool.Tool;

import java.util.List;

/**
 * 工具提供商 SPI。
 * <p>
 * 第三方实现此接口来批量注册自定义工具。
 * </p>
 */
public interface ToolProviderSpi {

    /**
     * @return 提供的工具列表
     */
    List<Tool> provideTools();

    /** @return 供应商名称 */
    default String getProviderName() {
        return getClass().getSimpleName();
    }
}
