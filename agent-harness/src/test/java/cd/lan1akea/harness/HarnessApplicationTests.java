package cd.lan1akea.harness;

import cd.lan1akea.harness.annotation.ToolFunction;
import cd.lan1akea.harness.annotation.HookSubscribe;
import cd.lan1akea.core.hook.HookEventType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * agent-harness 基础单元测试。
 */
class HarnessApplicationTests {

    @Test
    void testHarnessException() {
        HarnessException ex = new HarnessException("测试异常");
        assertEquals("测试异常", ex.getMessage());
    }

    @Test
    void testHarnessConfig() {
        HarnessConfig config = new HarnessConfig();
        assertFalse(config.isDebugMode());
        config.setDebugMode(true);
        assertTrue(config.isDebugMode());
    }

    @Test
    void testToolFunctionAnnotation() throws Exception {
        // 验证注解存在且可获取
        ToolFunction ann = AnnotatedClass.class.getAnnotation(ToolFunction.class);
        assertNotNull(ann);
        assertEquals("test_tool", ann.name());
    }

    @Test
    void testHookSubscribeAnnotation() throws Exception {
        HookSubscribe ann = AnnotatedHook.class.getAnnotation(HookSubscribe.class);
        assertNotNull(ann);
        assertEquals(1, ann.value().length);
        assertEquals(HookEventType.PRE_REASONING, ann.value()[0]);
    }

    @ToolFunction(name = "test_tool", description = "测试工具")
    static class AnnotatedClass {}

    @HookSubscribe({HookEventType.PRE_REASONING})
    static class AnnotatedHook {}
}
