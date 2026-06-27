package cd.lan1akea.core.message;

/**
 * 消息元数据键常量。
 * 定义 Msg.metadata 中使用的标准键名，避免魔法字符串。
 */
public final class MessageMetadataKeys {

    /**
     * 私有构造函数，防止实例化。
     */
    private MessageMetadataKeys() {
    }

    /**
     * 模型名称
     */
    public static final String MODEL_NAME = "model_name";

    /**
     * Token 用量
     */
    public static final String TOKEN_USAGE = "token_usage";

    /**
     * 推理耗时（毫秒）
     */
    public static final String LATENCY_MS = "latency_ms";

    /**
     * 是否被 Hook 修改过
     */
    public static final String HOOK_MODIFIED = "hook_modified";

    /**
     * 中断 ID（人机协同时使用）
     */
    public static final String INTERRUPT_ID = "interrupt_id";

    /**
     * 租户 ID
     */
    public static final String TENANT_ID = "tenant_id";

    /**
     * 会话 ID
     */
    public static final String SESSION_ID = "session_id";

    /**
     * 消息时间戳
     */
    public static final String TIMESTAMP = "timestamp";

    /**
     * 结构化输出类型
     */
    public static final String STRUCTURED_OUTPUT_TYPE = "structured_output_type";
}
