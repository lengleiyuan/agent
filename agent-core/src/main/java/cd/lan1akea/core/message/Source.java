package cd.lan1akea.core.message;

/**
 * 内容来源抽象基类（图片、音频、视频等多媒体内容的来源）。
 */
public abstract class Source {

    /** 来源类型 */
    private final String type;

    protected Source(String type) {
        this.type = type;
    }

    /** @return 来源类型 */
    public String getType() {
        return type;
    }
}
