package cd.lan1akea.core.model;

/**
 * 模型顶层接口。
 * <p>
 * 所有模型（聊天、嵌入、文生图等）的公共父接口。
 * </p>
 */
public interface Model {

    /**
     * @return 模型名称
     */
    String getModelName();

    /**
     * @return 提供商名称
     */
    String getProvider();
}
