package cd.lan1akea.core.message;

/**
 * 图片内容块。
 * 支持 URL 和 Base64 两种来源。
 */
public class ImageBlock extends ContentBlock {

    /**
     * 图片来源
     */
    private final Source source;

    /**
     * 可选：图片描述（alt text）
     */
    private final String altText;

    /**
     * 创建带有指定来源的图片块。
     *
     * @param source 图片来源
     */
    public ImageBlock(Source source) {
        this(source, null);
    }

    /**
     * 创建带有指定来源和描述文本的图片块。
     *
     * @param source  图片来源
     * @param altText 可选的图片描述
     */
    public ImageBlock(Source source, String altText) {
        super(TYPE_IMAGE);
        this.source = source;
        this.altText = altText;
    }

    /**
     * 通过 URL 创建图片块。
     *
     * @param url 图片URL
     * @return ImageBlock 实例
     */
    public static ImageBlock fromUrl(String url) {
        return new ImageBlock(new UrlSource(url));
    }

    /**
     * 通过 Base64 创建图片块。
     *
     * @param base64Data Base64 编码的图片数据
     * @param mediaType  MIME 类型（如 image/png）
     * @return ImageBlock 实例
     */
    public static ImageBlock fromBase64(String base64Data, String mediaType) {
        return new ImageBlock(new Base64Source(base64Data, mediaType));
    }

    /**
     * @return 图片来源
     */
    public Source getSource() { return source; }

    /**
     * @return 图片描述
     */
    public String getAltText() { return altText; }
}
