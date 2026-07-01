package cd.lan1akea.core.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON 序列化/反序列化工具，统一使用 fastjson2。
 */
public final class JsonUtils {

    private JsonUtils() {}

    private static final JSONWriter.Feature[] DEFAULT_WRITE_FEATURES = {
        JSONWriter.Feature.WriteMapNullValue,
        JSONWriter.Feature.PrettyFormat
    };

    public static String toJson(Object obj) {
        if (obj == null) return null;
        return JSON.toJSONString(obj, DEFAULT_WRITE_FEATURES);
    }

    public static String toCompactJson(Object obj) {
        if (obj == null) return null;
        return JSON.toJSONString(obj, JSONWriter.Feature.WriteMapNullValue);
    }

    // ======================== 严格解析（可抛异常） ========================

    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) return null;
        return JSON.parseObject(json, clazz);
    }

    @SuppressWarnings("unchecked")
    public static <T> T fromJson(String json, Type type) {
        if (json == null || json.isEmpty()) return null;
        return (T) JSON.parseObject(json, type);
    }

    public static <T> T convert(Object obj, Class<T> targetClass) {
        if (obj == null) return null;
        if (targetClass.isInstance(obj)) return targetClass.cast(obj);
        return fromJson(toCompactJson(obj), targetClass);
    }

    public static <T> T deepCopy(T obj, Class<T> clazz) {
        if (obj == null) return null;
        return fromJson(toCompactJson(obj), clazz);
    }

    // ======================== 安全解析（永不抛异常） ========================

    /**
     * 多级回退解析 JSON 为 Map，永不抛异常。
     *
     * 管线：严格解析 → repairJson 修复 → 正则兜底 → 空 Map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> safeParseMap(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyMap();

        // Level 1: 严格解析
        try {
            Map<String, Object> result = JSON.parseObject(raw, Map.class);
            if (result != null) return result;
        } catch (Exception ignored) {}

        // Level 2: repairJson 修复后解析
        String repaired = repairJson(raw);
        if (!repaired.equals(raw)) {
            try {
                Map<String, Object> result = JSON.parseObject(repaired, Map.class);
                if (result != null) return result;
            } catch (Exception ignored) {}
        }

        // Level 3: 正则兜底 — 从混乱文本中提取 "key": value 对
        Map<String, Object> extracted = regexExtractMap(raw);
        if (!extracted.isEmpty()) return extracted;
        extracted = regexExtractMap(repaired);
        if (!extracted.isEmpty()) return extracted;

        return Collections.emptyMap();
    }

    // ======================== JSON 修复 ========================

    /**
     * 修复 LLM 常见 JSON 格式错误。
     */
    public static String repairJson(String raw) {
        if (raw == null || raw.isEmpty() || isValidJson(raw)) return raw;

        String s = raw;

        // 1. 去除 ```json ... ``` 代码块包裹
        s = stripCodeFences(s);
        if (isValidJson(s)) return s;

        // 2. "key""value" → "key":"value"（缺失冒号）
        s = s.replaceAll("\"(\\w+)\"\"", "\"$1\":\"");
        if (isValidJson(s)) return s;

        // 3. "...} trailing text..." → 截取到最后一个 }
        s = trimToJson(s);
        if (isValidJson(s)) return s;

        // 4. value} → value"}（未闭合的字符串值）
        s = raw.replaceAll("\"(\\w+)\"\"", "\"$1\":\"");
        if (s.endsWith("}") && !s.endsWith("\"}") && !s.endsWith("\" }")) {
            int lastBrace = s.lastIndexOf('}');
            String candidate = s.substring(0, lastBrace) + "\"}";
            if (isValidJson(candidate)) return candidate;
        }

        return raw;
    }

    public static boolean isValidJson(String str) {
        if (str == null || str.isEmpty()) return false;
        try {
            JSON.parse(str);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ======================== 私有辅助 ========================

    /** 去除 ```json ... ``` 包裹 */
    private static String stripCodeFences(String s) {
        Pattern fencePattern = Pattern.compile(
            "```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```", Pattern.DOTALL);
        Matcher m = fencePattern.matcher(s);
        if (m.find()) return m.group(1).trim();
        return s;
    }

    /** 截取从 { 到最后一个 } 的内容 */
    private static String trimToJson(String s) {
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return s;
    }

    /** 正则兜底：从文本中提取 "key": value 或 "key": "value" 对 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> regexExtractMap(String s) {
        // 匹配 "key": "string_value" 或 "key": number 或 "key": true/false
        Pattern pairPattern = Pattern.compile(
            "\"(\\w+)\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|\\d+(?:\\.\\d+)?|true|false|null)");
        Matcher m = pairPattern.matcher(s);
        Map<String, Object> result = new LinkedHashMap<>();
        while (m.find()) {
            String key = m.group(1);
            String value = m.group(2);
            if (value.startsWith("\"") && value.endsWith("\"")) {
                result.put(key, value.substring(1, value.length() - 1));
            } else if ("true".equals(value)) {
                result.put(key, true);
            } else if ("false".equals(value)) {
                result.put(key, false);
            } else if ("null".equals(value)) {
                result.put(key, null);
            } else {
                try {
                    result.put(key, value.contains(".") ? Double.parseDouble(value) : Long.parseLong(value));
                } catch (NumberFormatException e) {
                    result.put(key, value);
                }
            }
        }
        return result;
    }
}
