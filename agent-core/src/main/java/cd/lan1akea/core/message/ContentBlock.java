package cd.lan1akea.core.message;

/**
 * 内容块抽象基类。
 * 一条消息（Msg）由一个或多个 ContentBlock 组成。
 * 不同类型的内容块承载不同类型的数据：文本、图片、音频、工具调用等。
 */
public abstract class ContentBlock {

    /**
     * 内容块类型标识
     */
    private final String type;

    /**
     * 创建指定类型的内容块。
     *
     * @param type 内容块类型标识
     */
    protected ContentBlock(String type) {
        this.type = type;
    }

    /**
     * @return 内容块类型
     */
    public String getType() {
        return type;
    }

    /**
     * 纯文本内容类型标识。
     */
    public static final String TYPE_TEXT = "text";
    /**
     * 图片内容类型标识。
     */
    public static final String TYPE_IMAGE = "image";
    /**
     * 音频内容类型标识。
     */
    public static final String TYPE_AUDIO = "audio";
    /**
     * 视频内容类型标识。
     */
    public static final String TYPE_VIDEO = "video";
    /**
     * 思考/推理内容类型标识。
     */
    public static final String TYPE_THINKING = "thinking";
    /**
     * 工具调用内容类型标识。
     */
    public static final String TYPE_TOOL_USE = "tool_use";
    /**
     * 工具结果内容类型标识。
     */
    public static final String TYPE_TOOL_RESULT = "tool_result";
    /**
     * 通用数据类型标识。
     */
    public static final String TYPE_DATA = "data";
    /**
     * 提示类型标识。
     */
    public static final String TYPE_HINT = "hint";
}
