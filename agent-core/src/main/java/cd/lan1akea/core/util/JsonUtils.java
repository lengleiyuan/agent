package cd.lan1akea.core.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.JSONReader;

import java.lang.reflect.Type;

/**
 * JSON 序列化工具，统一使用 fastjson2。
 * 提供常用的序列化/反序列化方法，统一配置（不输出null值、格式化输出等）。
 */
public final class JsonUtils {

    /**
     * 私有构造函数，防止实例化。
     */
    private JsonUtils() {
    }

    /**
     * 默认序列化特性：跳过null值、格式化输出
     */
    private static final JSONWriter.Feature[] DEFAULT_WRITE_FEATURES = {
        JSONWriter.Feature.WriteMapNullValue,
        JSONWriter.Feature.PrettyFormat
    };

    /**
     * 将对象序列化为JSON字符串。
     *
     * @param obj 待序列化对象
     * @return JSON字符串
     */
    public static String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        return JSON.toJSONString(obj, DEFAULT_WRITE_FEATURES);
    }

    /**
     * 将对象序列化为紧凑JSON字符串（无格式化）。
     *
     * @param obj 待序列化对象
     * @return 紧凑JSON字符串
     */
    public static String toCompactJson(Object obj) {
        if (obj == null) {
            return null;
        }
        return JSON.toJSONString(obj, JSONWriter.Feature.WriteMapNullValue);
    }

    /**
     * 将JSON字符串反序列化为指定类型。
     *
     * @param json  JSON字符串
     * @param clazz 目标类型
     * @param <T>   泛型类型
     * @return 反序列化后的对象
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        return JSON.parseObject(json, clazz);
    }

    /**
     * 将JSON字符串反序列化为指定类型（支持泛型）。
     *
     * @param json JSON字符串
     * @param type 目标类型（支持泛型 TypeReference）
     * @param <T>  泛型类型
     * @return 反序列化后的对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T fromJson(String json, Type type) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        return (T) JSON.parseObject(json, type);
    }

    /**
     * 将对象转换为另一类型（先序列化再反序列化）。
     *
     * @param obj       源对象
     * @param targetClass 目标类型
     * @param <T>       泛型类型
     * @return 转换后的对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T convert(Object obj, Class<T> targetClass) {
        if (obj == null) {
            return null;
        }
        if (targetClass.isInstance(obj)) {
            return (T) obj;
        }
        String json = toCompactJson(obj);
        return fromJson(json, targetClass);
    }

    /**
     * 深拷贝对象。
     *
     * @param obj   源对象
     * @param clazz 对象类型
     * @param <T>   泛型类型
     * @return 深拷贝对象
     */
    public static <T> T deepCopy(T obj, Class<T> clazz) {
        if (obj == null) {
            return null;
        }
        return fromJson(toCompactJson(obj), clazz);
    }

    /**
     * 判断字符串是否为合法JSON。
     *
     * @param str 待校验字符串
     * @return true 如果是合法JSON
     */
    public static boolean isValidJson(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        try {
            JSON.parse(str);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
