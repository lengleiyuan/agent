package cd.lan1akea.harness.annotation;

import java.lang.annotation.*;

/**
 * 自定义 Schema 覆盖注解。
 * <p>
 * 当自动生成的 Schema 不满足需求时，用此注解提供完整的 JSON Schema。
 * </p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolSchema {

    /** JSON Schema 字符串 */
    String value();
}
