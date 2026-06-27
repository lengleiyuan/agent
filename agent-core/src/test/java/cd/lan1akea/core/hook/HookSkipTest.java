package cd.lan1akea.core.hook;

import cd.lan1akea.core.model.ToolSchema;
import cd.lan1akea.core.tool.Tool;
import cd.lan1akea.core.tool.ToolCallContext;
import cd.lan1akea.core.tool.ToolRegistry;
import cd.lan1akea.core.tool.ToolResult;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class HookSkipTest {

    @Test
    void skipResultStopsHookChain() {
        HookChain chain = new HookChain();
        chain.register(new SkipHook("skipper", HookEventType.PRE_TOOL_CALL, "no permission"));
        AtomicH follower = new AtomicH("follower", HookEventType.PRE_TOOL_CALL);
        chain.register(follower);

        HookResult result = chain.fire(HookEventType.PRE_TOOL_CALL,
            new HookEvent(HookEventType.PRE_TOOL_CALL),
            new HookContext("a", "t", "s", "u", 0, null, null)).block();

        assertTrue(result.isSkip());
        assertEquals("no permission", result.getSkipReason());
        assertFalse(follower.executed, "SKIP 后不应执行后续 Hook");
    }

    @Test
    void skipDoesNotAffectOtherEventTypes() {
        HookChain chain = new HookChain();
        chain.register(new SkipHook("only-pre-tool", HookEventType.PRE_TOOL_CALL, "skip"));

        // POST_TOOL_CALL 不受影响
        HookResult result = chain.fire(HookEventType.POST_TOOL_CALL,
            new HookEvent(HookEventType.POST_TOOL_CALL),
            new HookContext("a", null, null, null, 0, null, null)).block();

        assertTrue(result.isContinue(), "不匹配的事件类型不受 SKIP 影响");
    }

    @Test
    void permissionHookReturnsSkip_toolNotExecuted() {
        // 模拟：权限 Hook 返回 SKIP → 工具不被执行
        ToolRegistry registry = new ToolRegistry();
        AtomicBoolean toolExecuted = new AtomicBoolean(false);

        Tool tool = new Tool() {
            @Override public String getName() { return "protected"; }
            @Override public String getDescription() { return ""; }
            @Override public ToolSchema getParameters() {
                return new ToolSchema("protected", "", Map.of());
            }
            @Override public Mono<ToolResult> execute(ToolCallContext p) {
                toolExecuted.set(true);
                return Mono.just(ToolResult.success("executed"));
            }
            @Override public Set<String> getPermissions() { return Set.of("admin"); }
        };
        registry.register(tool);

        // Hook: 检查权限 → 无权限 → SKIP
        PermissionSkipHook skipHook = new PermissionSkipHook(registry);
        ToolCallContext param = ToolCallContext.of("c1", "protected", Map.of());
        ToolCallEvent event = new ToolCallEvent(HookEventType.PRE_TOOL_CALL, param);

        HookResult result = skipHook.onEvent(event,
            new HookContext("a", "t", "s", "u", 0, null, null)).block();

        assertTrue(result.isSkip(), "无权限应返回 SKIP");
        assertTrue(result.getSkipReason().contains("无权限"));
        assertFalse(toolExecuted.get(), "工具不应被执行");
    }

    @Test
    void permissionHookReturnsContinue_toolWouldExecute() {
        ToolRegistry registry = new ToolRegistry();
        Tool tool = new Tool() {
            @Override public String getName() { return "public_tool"; }
            @Override public String getDescription() { return ""; }
            @Override public ToolSchema getParameters() {
                return new ToolSchema("public_tool", "", Map.of());
            }
            @Override public Mono<ToolResult> execute(ToolCallContext p) {
                return Mono.just(ToolResult.success("ok"));
            }
        };
        registry.register(tool); // no permissions → should pass

        PermissionSkipHook skipHook = new PermissionSkipHook(registry);
        ToolCallContext param = ToolCallContext.of("c2", "public_tool", Map.of());
        ToolCallEvent event = new ToolCallEvent(HookEventType.PRE_TOOL_CALL, param);

        HookResult result = skipHook.onEvent(event,
            new HookContext("a", null, null, null, 0, null, null)).block();

        assertTrue(result.isContinue(), "无权限要求的工具应继续");
    }

    // ========================================================================
    // helpers
    // ========================================================================

    static class SkipHook implements Hook {
        private final String name;
        private final HookEventType type;
        private final String reason;
        SkipHook(String name, HookEventType type, String reason) {
            this.name = name; this.type = type; this.reason = reason;
        }
        @Override public String getName() { return name; }
        @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(type); }
        @Override public Mono<HookResult> onEvent(HookEvent event, HookContext ctx) {
            return Mono.just(HookResult.skip(reason));
        }
    }

    static class AtomicH implements Hook {
        private final String name;
        private final HookEventType type;
        boolean executed;
        AtomicH(String name, HookEventType type) { this.name = name; this.type = type; }
        @Override public String getName() { return name; }
        @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(type); }
        @Override public Mono<HookResult> onEvent(HookEvent event, HookContext ctx) {
            executed = true;
            return Mono.just(HookResult.continue_());
        }
    }

    /** 模拟权限 Hook：有权限要求的工具 → SKIP；无要求的 → CONTINUE */
    static class PermissionSkipHook implements Hook {
        private final ToolRegistry registry;
        PermissionSkipHook(ToolRegistry registry) { this.registry = registry; }

        @Override public String getName() { return "perm-skip"; }
        @Override public Set<HookEventType> getSubscribedEventTypes() {
            return Set.of(HookEventType.PRE_TOOL_CALL);
        }

        @Override
        public Mono<HookResult> onEvent(HookEvent event, HookContext ctx) {
            if (!(event instanceof ToolCallEvent tce)) return Mono.just(HookResult.continue_());
            String toolName = tce.getCallParam().getToolName();
            Tool tool = registry.get(toolName);
            if (tool == null || tool.getPermissions().isEmpty())
                return Mono.just(HookResult.continue_());
            // 有权限要求 → 跳过（模拟无权限场景）
            return Mono.just(HookResult.skip(
                "无权限: tool=" + toolName + " 需要 " + tool.getPermissions()));
        }
    }
}
