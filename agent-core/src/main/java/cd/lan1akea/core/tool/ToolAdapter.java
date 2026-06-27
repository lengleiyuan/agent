package cd.lan1akea.core.tool;

import java.util.Collections;
import java.util.List;

/**
 * SPI 接口：将任意对象解析为 Tool。
 * agent-core 不依赖任何注解，通过此接口让 agent-harness 注入注解转换能力。
 */
public interface ToolAdapter {

    /**
     * 判断是否能处理该对象。
     */
    boolean canAdapt(Object obj);

    /**
     * 将对象解析为单个 Tool 实例。
     * 如果对象包含多个工具方法，返回第一个。
     * @see #adaptToAll(Object)
     */
    Tool adaption(Object obj);

    /**
     * 将对象适配为所有 Tool 实例（支持一个类多个 ToolFunction 方法）。
     * 默认委托到 adaption(Object)。
     */
    default List<Tool> adaptToAll(Object obj) {
        Tool t = adaption(obj);
        return t != null ? List.of(t) : Collections.emptyList();
    }
}
