package cd.lan1akea.core.message;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Msg 构建器。
 * <p>
 * 用法示例：
 * <pre>
 * Msg msg = Msg.builder(MsgRole.USER)
 *     .addText("你好")
 *     .addImage(ImageBlock.fromUrl("https://example.com/img.png"))
 *     .putMetadata("tenant_id", 123L)
 *     .build();
 * </pre>
 * </p>
 */
public class MsgBuilder {

    private final MsgRole role;
    private final List<ContentBlock> contentBlocks = new ArrayList<>();
    private final Map<String, Object> metadata = new HashMap<>();

    MsgBuilder(MsgRole role) {
        this.role = role;
    }

    /**
     * 添加文本内容块。
     *
     * @param text 文本内容
     * @return this
     */
    public MsgBuilder addText(String text) {
        if (text != null && !text.isEmpty()) {
            contentBlocks.add(new TextBlock(text));
        }
        return this;
    }

    /**
     * 添加任意内容块。
     *
     * @param block 内容块
     * @return this
     */
    public MsgBuilder addContentBlock(ContentBlock block) {
        if (block != null) {
            contentBlocks.add(block);
        }
        return this;
    }

    /**
     * 添加图片内容块（通过URL）。
     *
     * @param url 图片URL
     * @return this
     */
    public MsgBuilder addImage(String url) {
        contentBlocks.add(ImageBlock.fromUrl(url));
        return this;
    }

    /**
     * 添加工具调用块。
     *
     * @param id         调用ID
     * @param name       工具名称
     * @param arguments  参数JSON
     * @return this
     */
    public MsgBuilder addToolUse(String id, String name, String arguments) {
        contentBlocks.add(new ToolUseBlock(id, name, arguments));
        return this;
    }

    /**
     * 添加工具结果块。
     *
     * @param toolUseId 工具调用ID
     * @param content   结果内容
     * @param isError   是否出错
     * @return this
     */
    public MsgBuilder addToolResult(String toolUseId, String content, boolean isError) {
        contentBlocks.add(new ToolResultBlock(toolUseId, content, isError));
        return this;
    }

    /**
     * 添加思考内容块。
     *
     * @param thinking 思考文本
     * @return this
     */
    public MsgBuilder addThinking(String thinking) {
        if (thinking != null && !thinking.isEmpty()) {
            contentBlocks.add(new ThinkingBlock(thinking));
        }
        return this;
    }

    /**
     * 添加元数据。
     *
     * @param key   键
     * @param value 值
     * @return this
     */
    public MsgBuilder putMetadata(String key, Object value) {
        this.metadata.put(key, value);
        return this;
    }

    /**
     * 构建 Msg 实例。
     *
     * @return Msg 实例
     */
    public Msg build() {
        return new Msg(role, contentBlocks, metadata);
    }
}
