package cd.lan1akea.core.message;

/**
 * 通用数据块。
 * <p>
 * 用于承载未分类的结构化数据。
 * </p>
 */
public class DataBlock extends ContentBlock {

    /** 数据载荷 */
    private final Object data;

    public DataBlock(Object data) {
        super(TYPE_DATA);
        this.data = data;
    }

    /** @return 数据载荷 */
    public Object getData() { return data; }
}
