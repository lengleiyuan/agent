package cd.lan1akea.harness.support;

import cd.lan1akea.core.model.ToolSchema;
import cd.lan1akea.core.tool.Tool;
import cd.lan1akea.core.tool.ToolCallParam;
import cd.lan1akea.core.tool.ToolResolver;
import cd.lan1akea.core.tool.ToolResult;
import cd.lan1akea.core.util.JsonUtils;
import cd.lan1akea.harness.annotation.ToolFunction;
import cd.lan1akea.harness.annotation.ToolParam;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 注解工具解析器 — 将 {@code @ToolFunction} 标注的 POJO 反射解析为 {@link Tool}。
 */
public class AnnotationToolResolver implements ToolResolver {

    @Override
    public boolean canResolve(Object obj) {
        Class<?> clazz = obj.getClass();
        if (clazz.isAnnotationPresent(ToolFunction.class)) return true;
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isAnnotationPresent(ToolFunction.class)) return true;
        }
        return false;
    }

    @Override
    public Tool resolve(Object target) {
        Class<?> clazz = target.getClass();
        Method method;
        ToolFunction anno;

        // 1. 找方法级 @ToolFunction
        Method found = null;
        ToolFunction foundAnno = null;
        for (Method m : clazz.getDeclaredMethods()) {
            ToolFunction a = m.getAnnotation(ToolFunction.class);
            if (a != null) {
                found = m;
                foundAnno = a;
                break;
            }
        }

        if (found != null) {
            method = found;
            anno = foundAnno;
        } else {
            // 2. 类级 @ToolFunction → 取第一个 public 方法
            ToolFunction classAnno = clazz.getAnnotation(ToolFunction.class);
            if (classAnno == null) {
                throw new IllegalArgumentException(
                    "类 [" + clazz.getName() + "] 未标注 @ToolFunction");
            }
            anno = classAnno;
            Method publicMethod = null;
            for (Method m : clazz.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isPublic(m.getModifiers())
                    && m.getDeclaringClass() != Object.class) {
                    publicMethod = m;
                    break;
                }
            }
            if (publicMethod == null) {
                throw new IllegalArgumentException(
                    "类 [" + clazz.getName() + "] 没有 public 方法");
            }
            method = publicMethod;
        }

        method.setAccessible(true);

        // 3. 提取参数元数据
        List<ParamMeta> params = extractParams(method);

        // 4. 生成 name / description
        String name = !anno.name().isEmpty() ? anno.name() : toSnakeCase(clazz.getSimpleName());
        String description = !anno.description().isEmpty() ? anno.description() : name;

        // 5. 构建 ToolSchema
        ToolSchema schema = buildSchema(name, description, params);

        return new ResolvedTool(name, description, schema, target, method, params);
    }

    private List<ParamMeta> extractParams(Method method) {
        List<ParamMeta> result = new ArrayList<>();
        for (Parameter param : method.getParameters()) {
            ParamMeta meta = new ParamMeta();
            meta.javaType = param.getType();
            meta.type = inferType(meta.javaType);

            ToolParam tp = param.getAnnotation(ToolParam.class);
            if (tp != null) {
                meta.name = !tp.name().isEmpty() ? tp.name() : param.getName();
                meta.description = !tp.description().isEmpty() ? tp.description() : meta.name;
                meta.required = tp.required();
                meta.defaultValue = tp.defaultValue();
            } else {
                meta.name = param.getName();
                meta.description = param.getName();
            }
            result.add(meta);
        }
        return result;
    }

    private ToolSchema buildSchema(String name, String description, List<ParamMeta> params) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (ParamMeta p : params) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", p.type);
            prop.put("description", p.description);
            if (p.defaultValue != null && !p.defaultValue.isEmpty()) {
                prop.put("default", p.defaultValue);
            }
            properties.put(p.name, prop);
            if (p.required) required.add(p.name);
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);

        return new ToolSchema(name, description, schema);
    }

    private String inferType(Class<?> clazz) {
        if (clazz == String.class) return "string";
        if (clazz == int.class || clazz == long.class || clazz == Integer.class || clazz == Long.class)
            return "integer";
        if (clazz == double.class || clazz == float.class || clazz == Double.class || clazz == Float.class)
            return "number";
        if (clazz == boolean.class || clazz == Boolean.class) return "boolean";
        return "string";
    }

    private static Object convertArg(Object raw, Class<?> targetType) {
        if (raw == null) return null;
        if (targetType.isInstance(raw)) return raw;
        String s = raw.toString();
        if (targetType == int.class || targetType == Integer.class) {
            if (raw instanceof Number n) return n.intValue();
            return Integer.parseInt(s);
        }
        if (targetType == long.class || targetType == Long.class) {
            if (raw instanceof Number n) return n.longValue();
            return Long.parseLong(s);
        }
        if (targetType == double.class || targetType == Double.class) {
            if (raw instanceof Number n) return n.doubleValue();
            return Double.parseDouble(s);
        }
        if (targetType == float.class || targetType == Float.class) {
            if (raw instanceof Number n) return n.floatValue();
            return Float.parseFloat(s);
        }
        if (targetType == boolean.class || targetType == Boolean.class) return Boolean.valueOf(s);
        return s;
    }

    static String toSnakeCase(String camel) {
        StringBuilder sb = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                if (sb.length() > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ========================================================================
    // inner types
    // ========================================================================

    private static class ParamMeta {
        String name;
        String type = "string";
        String description = "";
        boolean required;
        String defaultValue;
        Class<?> javaType = String.class;
    }

    private static class ResolvedTool implements Tool {
        private final String name;
        private final String description;
        private final ToolSchema schema;
        private final Object target;
        private final Method method;
        private final List<ParamMeta> params;

        ResolvedTool(String name, String description, ToolSchema schema,
                     Object target, Method method, List<ParamMeta> params) {
            this.name = name;
            this.description = description;
            this.schema = schema;
            this.target = target;
            this.method = method;
            this.params = params;
        }

        @Override public String getName() { return name; }
        @Override public String getDescription() { return description; }
        @Override public ToolSchema getParameters() { return schema; }

        @Override
        public Mono<ToolResult> execute(ToolCallParam callParam) {
            return Mono.fromCallable(() -> {
                Object[] args = new Object[params.size()];
                for (int i = 0; i < params.size(); i++) {
                    ParamMeta p = params.get(i);
                    Object raw = callParam.get(p.name);
                    if (raw == null && p.defaultValue != null && !p.defaultValue.isEmpty()) {
                        raw = p.defaultValue;
                    }
                    args[i] = convertArg(raw, p.javaType);
                }
                Object result = method.invoke(target, args);
                String output = result instanceof String ? (String) result : JsonUtils.toCompactJson(result);
                return ToolResult.success(output);
            }).onErrorResume(e -> Mono.just(ToolResult.failure(e.getMessage())));
        }
    }
}
