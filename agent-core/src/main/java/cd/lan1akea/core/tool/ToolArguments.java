package cd.lan1akea.core.tool;

import cd.lan1akea.core.util.JsonUtils;

import java.util.Collections;
import java.util.Map;

/**
 * 工具参数（LLM 发出的工具调用参数）。
 * 封装 LLM 传来的 name=value 参数，提供类型安全访问。
 * 从 JSON string 构造时仅 parse 一次。
 */
public class ToolArguments {

    /**
     * 不可修改的参数键值对
     */
    private final Map<String, Object> args;

    /**
     * 构造一个参数实例。
     *
     * @param args 参数键值对，null 视为空 Map
     */
    public ToolArguments(Map<String, Object> args) {
        this.args = args != null
            ? Collections.unmodifiableMap(args)
            : Collections.emptyMap();
    }

    /**
     * 从 JSON 字符串解析构造参数实例。
     *
     * @param json JSON 字符串
     * @return 参数实例
     */
    public static ToolArguments fromJson(String json) {
        return new ToolArguments(JsonUtils.safeParseMap(json));
    }

    /**
     * 获取指定名称的参数值。
     *
     * @param name 参数名称
     * @param <T>  参数值类型
     * @return 参数值，可能为 null
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String name) { return (T) args.get(name); }

    /**
     * 获取指定名称的字符串参数值。
     *
     * @param name 参数名称
     * @return 字符串值，可能为 null
     */
    public String getString(String name) {
        Object val = args.get(name);
        return val != null ? val.toString() : null;
    }

    /**
     * 获取指定名称的数字参数值。
     *
     * @param name 参数名称
     * @return 数字值，可能为 null
     */
    public Number getNumber(String name) {
        Object val = args.get(name);
        return val instanceof Number n ? n : null;
    }

    /**
     * 获取指定名称的布尔参数值。
     *
     * @param name 参数名称
     * @return 布尔值，可能为 null
     */
    public Boolean getBoolean(String name) {
        Object val = args.get(name);
        return val instanceof Boolean b ? b : null;
    }

    /**
     * @return 不可修改的参数 Map
     */
    public Map<String, Object> asMap() { return args; }

    /**
     * @return 参数的 JSON 紧凑字符串表示
     */
    public String toJson() { return JsonUtils.toCompactJson(args); }

    /**
     * @return 参数是否为空
     */
    public boolean isEmpty() { return args.isEmpty(); }

    /**
     * @return 参数的字符串表示
     */
    @Override
    public String toString() { return args.toString(); }
}
