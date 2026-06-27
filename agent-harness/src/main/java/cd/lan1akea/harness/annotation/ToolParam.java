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
}
