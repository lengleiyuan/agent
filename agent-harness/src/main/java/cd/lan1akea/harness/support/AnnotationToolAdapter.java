package cd.lan1akea.harness.support;

import cd.lan1akea.core.model.ToolSchema;
import cd.lan1akea.core.tool.Tool;
import cd.lan1akea.core.tool.ToolCallContext;
import cd.lan1akea.core.tool.ToolAdapter;
import cd.lan1akea.core.tool.ToolResult;
import cd.lan1akea.core.util.ArgumentConverter;
import cd.lan1akea.core.util.JsonUtils;
import cd.lan1akea.core.util.TypeSchemaGenerator;
import cd.lan1akea.harness.annotation.ToolFunction;
import cd.lan1akea.harness.annotation.ToolParam;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 注解工具解析器——将 @ToolFunction 标注的 POJO 反射解析为 Tool。
 */
public class AnnotationToolAdapter implements ToolAdapter {

    /**
     * 判断对象是否适配（有类级或方法级 ToolFunction 注解）。
     */
    @Override
    public boolean canAdapt(Object obj) {
        Class<?> clazz = obj.getClass();
        if (clazz.isAnnotationPresent(ToolFunction.class)) return true;
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isAnnotationPresent(ToolFunction.class)) return true;
        }
        return false;
    }

    /**
     * 将目标对象适配为单个 Tool。
     */
    @Override
    public Tool adaption(Object target) {
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

        // 6. 提取注解配置
        Set<String> permissions = anno.permission().isEmpty()
            ? Collections.emptySet() : Set.of(anno.permission());
        int ctxParamIndex = findContextParam(method);

        return new AnnotationTool(name, description, schema, target, method, params,
            permissions, ctxParamIndex, anno.group(), anno.timeoutMs(), anno.riskLevel());
    }

    /**
     * 将目标对象适配为多个 Tool（类级或方法级注解）。
     */
    @Override
    public List<Tool> adaptToAll(Object target) {
        Class<?> clazz = target.getClass();
        List<Method> methods = findToolMethods(clazz);

        List<Tool> tools = new ArrayList<>();
        for (Method method : methods) {
            method.setAccessible(true);
            ToolFunction anno = method.getAnnotation(ToolFunction.class);
            if (anno == null) anno = clazz.getAnnotation(ToolFunction.class); // 类级回退

            List<ParamMeta> params = extractParams(method);
            String name = !anno.name().isEmpty() ? anno.name() : toSnakeCase(method.getName());
            String desc = !anno.description().isEmpty() ? anno.description() : name;
            ToolSchema schema = buildSchema(name, desc, params);
            Set<String> permissions = anno.permission().isEmpty()
                ? Collections.emptySet() : Set.of(anno.permission());
            int ctxParamIndex = findContextParam(method);

            tools.add(new AnnotationTool(name, desc, schema, target, method, params,
                permissions, ctxParamIndex,
                anno.group(), anno.timeoutMs(), anno.riskLevel()));
        }
        return tools;
    }

    /**
     * 查找所有工具方法：
     * 1. 有方法级 ToolFunction 返回所有标注了 ToolFunction 的方法
     * 2. 只有类级 ToolFunction 返回所有 public 非 Object 方法
     */
    private List<Method> findToolMethods(Class<?> clazz) {
        List<Method> annotated = new ArrayList<>();
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isAnnotationPresent(ToolFunction.class)) annotated.add(m);
        }
        if (!annotated.isEmpty()) return annotated;

        // 类级 @ToolFunction：所有 public 方法
        if (clazz.isAnnotationPresent(ToolFunction.class)) {
            for (Method m : clazz.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isPublic(m.getModifiers())
                    && m.getDeclaringClass() != Object.class) {
                    annotated.add(m);
                }
            }
        }
        return annotated;
    }

    /**
     * 提取方法参数元数据列表。
     */
    private List<ParamMeta> extractParams(Method method) {
        List<ParamMeta> result = new ArrayList<>();
        for (Parameter param : method.getParameters()) {
            // 跳过框架注入参数 — ToolCallContext 不出现在 LLM Schema 中
            if (param.getType() == cd.lan1akea.core.tool.ToolCallContext.class) continue;

            ParamMeta meta = new ParamMeta();
            meta.javaType = param.getType();
            meta.javaGenericType = param.getParameterizedType();

            // TypeSchemaGenerator 统一生成 schema，简单类型提取 type 字符串，复杂类型存完整 schema
            Type schemaType = meta.javaGenericType != null ? meta.javaGenericType : meta.javaType;
            Map<String, Object> generated = TypeSchemaGenerator.generate(schemaType);
            if (isSimpleSchema(generated)) {
                meta.type = (String) generated.get("type");
            } else {
                meta.schema = generated;
            }

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

    /**
     * 根据名称、描述和参数元数据构建 ToolSchema。
     */
    private ToolSchema buildSchema(String name, String description, List<ParamMeta> params) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (ParamMeta p : params) {
            Map<String, Object> prop;
            if (p.schema != null) {
                // 复杂类型：直接使用完整 schema
                prop = new LinkedHashMap<>(p.schema);
            } else {
                prop = new LinkedHashMap<>();
                prop.put("type", p.type);
            }
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

    private static boolean isSimpleSchema(Map<String, Object> schema) {
        String type = (String) schema.get("type");
        return schema.size() == 1 && type != null
            && !"object".equals(type) && !"array".equals(type);
    }

    /**
     * 将原始参数值转换为目标类型，统一委托 {@link ArgumentConverter}。
     */
    private static Object convertArg(Object raw, ParamMeta meta) {
        if (raw == null) return null;
        Type targetType = meta.javaGenericType != null ? meta.javaGenericType : meta.javaType;
        return ArgumentConverter.convert(raw, targetType);
    }

    /**
     * 查找 ToolCallContext（core 或 harness 门面）类型的参数位置，未找到返回 -1。
     */
    private static int findContextParam(Method method) {
        Parameter[] params = method.getParameters();
        for (int i = 0; i < params.length; i++) {
            if (params[i].getType() == cd.lan1akea.core.tool.ToolCallContext.class) return i;
        }
        return -1;
    }

    private static Object buildContextArg(ToolCallContext callParam) {
        return callParam;
    }

    /**
     * 将驼峰命名转换为蛇形命名。
     */
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

    /**
     * 参数元数据，描述工具方法的一个参数。
     */
    private static class ParamMeta {
        /**
         * 参数名称。
         */
        String name;
        /**
         * JSON Schema 类型（string/number/integer/boolean）；复杂类型时为 null。
         */
        String type = "string";
        /**
         * 参数描述。
         */
        String description = "";
        /**
         * 是否必需。
         */
        boolean required;
        /**
         * 默认值。
         */
        String defaultValue;
        /**
         * 参数的 Java 类型。
         */
        Class<?> javaType = String.class;
        /**
         * 参数的泛型类型（用于 List&lt;Pojo&gt; 等）。
         */
        Type javaGenericType;
        /**
         * 复杂类型的完整 JSON Schema；为 null 表示使用 type 生成简单 schema。
         */
        Map<String, Object> schema;
    }

    /**
     * 基于反射的注解工具实现。
     */
    private static class AnnotationTool implements Tool {
        /**
         * 工具名称。
         */
        private final String name;
        /**
         * 工具描述。
         */
        private final String description;
        /**
         * 参数 Schema。
         */
        private final ToolSchema schema;
        /**
         * 目标对象实例。
         */
        private final Object target;
        /**
         * 目标方法。
         */
        private final Method method;
        /**
         * 参数元数据列表。
         */
        private final List<ParamMeta> params;
        /**
         * 业务权限码集合。
         */
        private final Set<String> permissions;
        /**
         * ToolCallContext 在方法参数中的位置，-1 表示不需要。
         */
        private final int ctxParamIndex;
        private final String group;
        private final long timeoutMs;
        private final String riskLevel;

        AnnotationTool(String name, String description, ToolSchema schema,
                     Object target, Method method, List<ParamMeta> params,
                     Set<String> permissions, int ctxParamIndex,
                     String group, long timeoutMs, String riskLevel) {
            this.name = name;
            this.description = description;
            this.schema = schema;
            this.target = target;
            this.method = method;
            this.params = params;
            this.permissions = permissions;
            this.ctxParamIndex = ctxParamIndex;
            this.group = group != null ? group : "default";
            this.timeoutMs = timeoutMs;
            this.riskLevel = riskLevel != null ? riskLevel : "MEDIUM";
        }

        /**
         * 返回工具名称。
         */
        @Override public String getName() { return name; }
        /**
         * 返回工具描述。
         */
        @Override public String getDescription() { return description; }
        /**
         * 返回参数 Schema。
         */
        @Override public ToolSchema getParameters() { return schema; }
        /**
         * 返回业务权限码。
         */
        @Override public Set<String> getPermissions() { return permissions; }
        @Override public String getGroup() { return group; }
        @Override public long getTimeoutMs() { return timeoutMs; }
        @Override public String getRiskLevel() { return riskLevel; }

        /**
         * 执行工具调用，通过反射调用目标方法。
         */
        @Override
        public Mono<ToolResult> execute(ToolCallContext callParam) {
            return Mono.fromCallable(() -> {
                int argCount = params.size() + (ctxParamIndex >= 0 ? 1 : 0);
                Object[] args = new Object[argCount];
                int argIdx = 0;
                // 1. 业务参数（LLM 可见参数）
                for (int i = 0; i < params.size(); i++) {
                    ParamMeta p = params.get(i);
                    Object raw = callParam.get(p.name);
                    if (raw == null && p.defaultValue != null && !p.defaultValue.isEmpty()) {
                        raw = p.defaultValue;
                    }
                    // 如果这个位置是 ToolCallContext 注入点，注入门面包
                    if (i == ctxParamIndex) {
                        args[argIdx++] = buildContextArg(callParam);
                    }
                    args[argIdx++] = convertArg(raw, p);
                }
                // 2. ToolCallContext 注入在参数列表末尾
                if (ctxParamIndex >= params.size()) {
                    args[argIdx] = callParam;
                }
                Object result = method.invoke(target, args);
                if (result instanceof ToolResult tr) return tr;
                String output = result instanceof String ? (String) result : JsonUtils.toCompactJson(result);
                return ToolResult.success(output);
            }).onErrorResume(e -> {
                Throwable cause = e instanceof java.lang.reflect.InvocationTargetException
                    ? e.getCause() : e;
                if (cause instanceof cd.lan1akea.core.exception.HumanInterventionException) {
                    return Mono.error(cause);
                }
                String msg = cause != null ? cause.getMessage() : e.getMessage();
                return Mono.just(ToolResult.failure(msg != null ? msg : "执行异常"));
            });
        }
    }
}
