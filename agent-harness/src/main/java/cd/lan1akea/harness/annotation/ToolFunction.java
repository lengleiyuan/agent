package cd.lan1akea.harness.annotation;

import java.lang.annotation.*;

/**
 * 标记类或方法为工具函数。
 * <p>
 * 标注在类上：整个类的 public 方法都可作为工具函数。
 * 标注在方法上：该方法为工具函数。
 * </p>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolFunction {

    /** 工具名称（对 LLM 可见），不指定则使用类名/方法名的蛇形形式 */
    String name() default "";

    /** 工具描述 */
    String description() default "";
}
