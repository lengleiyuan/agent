package cd.lan1akea.core.message;

import cd.lan1akea.core.util.IdGenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 消息顶层类。
 * <p>
 * 消息由角色（MsgRole）和一个或多个内容块（ContentBlock）组成。
 * 附带 metadata 映射用于存储扩展信息（Token用量、延迟、中断ID等）。
 * 使用 MsgBuilder 构建实例。
 * </p>
 */
public class Msg {

    /** 消息唯一ID */
    private final String id;

    /** 消息角色 */
    private final MsgRole role;

    /** 内容块列表（不可变） */
    private final List<ContentBlock> contentBlocks;

    /** 扩展元数据 */
    private final Map<String, Object> metadata;

    /**
     * 包级可见构造函数，通过 MsgBuilder 构建。
     */
    Msg(MsgRole role, List<ContentBlock> contentBlocks, Map<String, Object> metadata) {
        this.id = IdGenerator.nextIdStr();
        this.role = Objects.requireNonNull(role, "消息角色不能为null");
        this.contentBlocks = Collections.unmodifiableList(
            new ArrayList<>(contentBlocks != null ? contentBlocks : Collections.emptyList()));
        this.metadata = Collections.unmodifiableMap(
            new HashMap<>(metadata != null ? metadata : Collections.emptyMap()));
    }

    /**
     * 创建 MsgBuilder。
     *
     * @param role 消息角色
     * @return MsgBuilder 实例
     */
    public static MsgBuilder builder(MsgRole role) {
        return new MsgBuilder(role);
    }

    /**
     * 提取消息中所有文本内容块的内容，拼接返回。
     *
     * @return 拼接后的文本
     */
    public String getTextContent() {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : contentBlocks) {
            if (block instanceof TextBlock) {
                sb.append(((TextBlock) block).getText());
            }
        }
        return sb.toString();
    }

    /**
     * 判断消息是否包含工具调用。
     *
     * @return true 如果包含 ToolUseBlock
     */
    public boolean hasToolCalls() {
        for (ContentBlock block : contentBlocks) {
            if (block instanceof ToolUseBlock) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取所有工具调用块。
     *
     * @return ToolUseBlock 列表
     */
    public List<ToolUseBlock> getToolUseBlocks() {
        List<ToolUseBlock> result = new ArrayList<>();
        for (ContentBlock block : contentBlocks) {
            if (block instanceof ToolUseBlock) {
                result.add((ToolUseBlock) block);
            }
        }
        return result;
    }

    /**
     * 获取指定类型的内容块。
     *
     * @param clazz 内容块类型
     * @param <T>   泛型
     * @return 匹配的内容块列表
     */
    @SuppressWarnings("unchecked")
    public <T extends ContentBlock> List<T> getContentBlocks(Class<T> clazz) {
        List<T> result = new ArrayList<>();
        for (ContentBlock block : contentBlocks) {
            if (clazz.isInstance(block)) {
                result.add((T) block);
            }
        }
        return result;
    }

    // === Getters ===

    /** @return 消息ID */
    public String getId() { return id; }

    /** @return 消息角色 */
    public MsgRole getRole() { return role; }

    /** @return 内容块列表（不可变） */
    public List<ContentBlock> getContentBlocks() { return contentBlocks; }

    /** @return 元数据（不可变） */
    public Map<String, Object> getMetadata() { return metadata; }

    /** 获取元数据值 */
    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key) {
        return (T) metadata.get(key);
    }

    @Override
    public String toString() {
        return "Msg{id='" + id + "', role=" + role
            + ", blocks=" + contentBlocks.size()
            + ", text='" + getTextContent() + "'}";
    }
}
