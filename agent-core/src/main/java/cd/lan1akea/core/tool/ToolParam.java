package cd.lan1akea.core.tool;

/**
 * 工具参数定义。
 * 描述工具的单个参数，用于生成 JSON Schema。
 */
public class ToolParam {

    /**
     * 参数名称
     */
    private final String name;

    /**
     * 参数类型（string、number、boolean、object、array）
     */
    private final String type;

    /**
     * 参数描述
     */
    private final String description;

    /**
     * 是否必需
     */
    private final boolean required;

    /**
     * 默认值
     */
    private final Object defaultValue;

    /**
     * 枚举可选值（仅当类型为 string 时有效）
     */
    private final String[] enumValues;

    private ToolParam(Builder builder) {
        this.name = builder.name;
        this.type = builder.type;
        this.description = builder.description;
        this.required = builder.required;
        this.defaultValue = builder.defaultValue;
        this.enumValues = builder.enumValues;
    }

    /**
     * @return 参数名称
     */
    public String getName() { return name; }
    /**
     * @return 参数类型
     */
    public String getType() { return type; }
    /**
     * @return 参数描述
     */
    public String getDescription() { return description; }
    /**
     * @return 是否必需
     */
    public boolean isRequired() { return required; }
    /**
     * @return 默认值
     */
    public Object getDefaultValue() { return defaultValue; }
    /**
     * @return 枚举可选值
     */
    public String[] getEnumValues() { return enumValues; }

    /**
     * 创建参数构建器。
     *
     * @param name 参数名称
     * @param type 参数类型
     * @return 构建器
     */
    public static Builder builder(String name, String type) {
        return new Builder(name, type);
    }

    public static class Builder {
        /**
         * 参数名称
         */
        private final String name;
        /**
         * 参数类型
         */
        private final String type;
        /**
         * 参数描述
         */
        private String description;
        /**
         * 是否必需
         */
        private boolean required;
        /**
         * 默认值
         */
        private Object defaultValue;
        /**
         * 枚举可选值
         */
        private String[] enumValues;

        Builder(String name, String type) {
            this.name = name;
            this.type = type;
        }

        /**
         * @param description 参数描述
         */
        public Builder description(String description) { this.description = description; return this; }
        /**
         * @param required 是否必需
         */
        public Builder required(boolean required) { this.required = required; return this; }
        /**
         * @param defaultValue 默认值
         */
        public Builder defaultValue(Object defaultValue) { this.defaultValue = defaultValue; return this; }
        /**
         * @param enumValues 枚举可选值
         */
        public Builder enumValues(String... enumValues) { this.enumValues = enumValues; return this; }

        /**
         * @return 构建的 ToolParam 实例
         */
        public ToolParam build() { return new ToolParam(this); }
    }
}
