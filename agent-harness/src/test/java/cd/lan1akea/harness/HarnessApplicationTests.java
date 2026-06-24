package cd.lan1akea.harness;

import cd.lan1akea.harness.annotation.ToolFunction;
import cd.lan1akea.harness.annotation.ToolParam;
import cd.lan1akea.core.hook.Hook;
import cd.lan1akea.core.hook.HookEventType;
import cd.lan1akea.core.hook.HookEvent;
import cd.lan1akea.core.hook.HookContext;
import cd.lan1akea.core.hook.HookResult;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HarnessApplicationTests {

    @Test
    void testToolFunctionAnnotation() throws Exception {
        ToolFunction ann = AnnotatedTool.class.getAnnotation(ToolFunction.class);
        assertNotNull(ann);
        assertEquals("test_tool", ann.name());
        assertEquals("测试工具", ann.description());
    }

    @Test
    void testToolParamAnnotation() throws Exception {
        var method = AnnotatedTool.class.getDeclaredMethod("execute",
            String.class);
        var params = method.getParameters();
        ToolParam p = params[0].getAnnotation(ToolParam.class);
        assertNotNull(p);
        assertEquals("city", p.name());
        assertEquals("城市名称", p.description());
        assertTrue(p.required());
    }

    @Test
    void testDirectHookImplementation() {
        Hook hook = new Hook() {
            @Override public String getName() { return "test-hook"; }
            @Override public Set<HookEventType> getSubscribedEventTypes() {
                return Set.of(HookEventType.PRE_REASONING);
            }
            @Override
            public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
                return Mono.just(HookResult.continue_());
            }
        };

        assertEquals("test-hook", hook.getName());
        assertTrue(hook.getSubscribedEventTypes().contains(HookEventType.PRE_REASONING));
        assertEquals(100, hook.getPriority());
    }

    @ToolFunction(name = "test_tool", description = "测试工具")
    static class AnnotatedTool {
        public void execute(@ToolParam(name = "city", description = "城市名称", required = true) String city) {}
    }
}
