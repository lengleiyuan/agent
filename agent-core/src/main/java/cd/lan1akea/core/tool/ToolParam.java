package cd.lan1akea.core.tool;

/**
 * 工具参数定义。
 * <p>
 * 描述工具的单个参数，用于生成 JSON Schema。
 * </p>
 */
public class ToolParam {

    /** 参数名称 */
    private final String name;

    /** 参数类型（string、number、boolean、object、array） */
    private final String type;

    /** 参数描述 */
    private final String description;

    /** 是否必需 */
    private final boolean required;

    /** 默认值 */
    private final Object defaultValue;

    /** 枚举可选值（仅当类型为 string 时有效） */
    private final String[] enumValues;

    private ToolParam(Builder builder) {
        this.name = builder.name;
        this.type = builder.type;
        this.description = builder.description;
        this.required = builder.required;
        this.defaultValue = builder.defaultValue;
        this.enumValues = builder.enumValues;
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public String getDescription() { return description; }
    public boolean isRequired() { return required; }
    public Object getDefaultValue() { return defaultValue; }
    public String[] getEnumValues() { return enumValues; }

    public static Builder builder(String name, String type) {
        return new Builder(name, type);
    }

    public static class Builder {
        private final String name;
        private final String type;
        private String description;
        private boolean required;
        private Object defaultValue;
        private String[] enumValues;

        Builder(String name, String type) {
            this.name = name;
            this.type = type;
        }

        public Builder description(String description) { this.description = description; return this; }
        public Builder required(boolean required) { this.required = required; return this; }
        public Builder defaultValue(Object defaultValue) { this.defaultValue = defaultValue; return this; }
        public Builder enumValues(String... enumValues) { this.enumValues = enumValues; return this; }

        public ToolParam build() { return new ToolParam(this); }
    }
}
