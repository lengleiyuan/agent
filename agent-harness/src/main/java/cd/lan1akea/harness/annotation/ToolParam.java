package cd.lan1akea.harness.annotation;

import java.lang.annotation.*;

/**
 * 工具参数注解。
 * 标注在工具方法的参数上，用于生成参数 Schema。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolParam {

    /**
     * 参数名称
     */
    String name();

    /**
     * 参数描述
     */
    String description() default "";

    /**
     * 是否必需
     */
    boolean required() default false;

    /**
     * 默认值
     */
    String defaultValue() default "";

    /**
     * 枚举可选值（仅 string 类型有效）
     */
    String[] enumValues() default {};

    /**
     * 最小值（仅 number 类型有效）
     */
    double minValue() default Double.NaN;

    /**
     * 最大值（仅 number 类型有效）
     */
    double maxValue() default Double.NaN;
}
