package cd.lan1akea.core.message;

/**
 * 内容来源抽象基类（图片、音频、视频等多媒体内容的来源）。
 */
public abstract class Source {

    /**
     * 来源类型
     */
    private final String type;

    /**
     * 创建指定类型的来源。
     *
     * @param type 来源类型标识
     */
    protected Source(String type) {
        this.type = type;
    }

    /**
     * @return 来源类型
     */
    public String getType() {
        return type;
    }
}
