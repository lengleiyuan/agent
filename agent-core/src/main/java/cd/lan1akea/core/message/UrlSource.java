package cd.lan1akea.core.message;

/**
 * URL 来源，多媒体内容通过 URL 引用。
 */
public class UrlSource extends Source {

    /**
     * 资源URL
     */
    private final String url;

    /**
     * 创建 URL 来源。
     *
     * @param url 资源 URL
     */
    public UrlSource(String url) {
        super("url");
        this.url = url;
    }

    /**
     * @return 资源URL
     */
    public String getUrl() {
        return url;
    }
}
