package cd.lan1akea.core.message;

/**
 * Base64 来源，多媒体内容以 Base64 编码内联传输。
 */
public class Base64Source extends Source {

    /**
     * Base64 编码的数据
     */
    private final String data;

    /**
     * MIME 类型
     */
    private final String mediaType;

    /**
     * 创建 Base64 来源。
     *
     * @param data      Base64 编码数据
     * @param mediaType 数据 MIME 类型
     */
    public Base64Source(String data, String mediaType) {
        super("base64");
        this.data = data;
        this.mediaType = mediaType;
    }

    /**
     * @return Base64 编码数据
     */
    public String getData() {
        return data;
    }

    /**
     * @return MIME 类型
     */
    public String getMediaType() {
        return mediaType;
    }
}
