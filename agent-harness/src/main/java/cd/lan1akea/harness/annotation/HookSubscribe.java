package cd.lan1akea.harness.annotation;

import cd.lan1akea.core.hook.HookEventType;

import java.lang.annotation.*;

/**
 * Hook 订阅注解。
 * <p>
 * 标注在 Hook 实现类上，声明该 Hook 订阅哪些事件类型。
 * </p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface HookSubscribe {

    /** 订阅的事件类型 */
    HookEventType[] value();

    /** 优先级，数值越小越先执行 */
    int priority() default 100;
}
