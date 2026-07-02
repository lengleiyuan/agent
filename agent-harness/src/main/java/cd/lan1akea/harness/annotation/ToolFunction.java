package cd.lan1akea.harness.annotation;

import java.lang.annotation.*;

/**
 * 标记类或方法为工具函数。
 * 标注在类上：整个类的 public 方法都可作为工具函数。
 * 标注在方法上：该方法为工具函数。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolFunction {

    /**
     * 工具名称（对 LLM 可见），不指定则使用类名/方法名的蛇形形式
     */
    String name() default "";

    /**
     * 工具描述
     */
    String description() default "";

    /**
     * 业务权限码。框架不强制校验，仅透传给 Hook（如 PermissionHook）。
     */
    String permission() default "";

    /**
     * 工具分组名（用于工具注册表分组展示）
     */
    String group() default "default";

    /**
     * 执行超时时间（毫秒），0 表示不超时
     */
    long timeoutMs() default 30000;

    /**
     * 风险等级，用于审批页面展示。LOW / MEDIUM / HIGH / CRITICAL
     */
    String riskLevel() default "MEDIUM";
}
