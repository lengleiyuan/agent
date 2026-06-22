package cd.lan1akea.core.message;

/**
 * 内容块抽象基类。
 * <p>
 * 一条消息（Msg）由一个或多个 ContentBlock 组成。
 * 不同类型的内容块承载不同类型的数据：文本、图片、音频、工具调用等。
 * </p>
 */
public abstract class ContentBlock {

    /** 内容块类型标识 */
    private final String type;

    protected ContentBlock(String type) {
        this.type = type;
    }

    /** @return 内容块类型 */
    public String getType() {
        return type;
    }

    // 类型常量
    public static final String TYPE_TEXT = "text";
    public static final String TYPE_IMAGE = "image";
    public static final String TYPE_AUDIO = "audio";
    public static final String TYPE_VIDEO = "video";
    public static final String TYPE_THINKING = "thinking";
    public static final String TYPE_TOOL_USE = "tool_use";
    public static final String TYPE_TOOL_RESULT = "tool_result";
    public static final String TYPE_DATA = "data";
    public static final String TYPE_HINT = "hint";
}
