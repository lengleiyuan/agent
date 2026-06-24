package cd.lan1akea.harness;
import java.util.Set;

import cd.lan1akea.core.agent.ReActAgent;
import cd.lan1akea.core.agent.config.AgentConfig;
import cd.lan1akea.core.agent.config.AgentExecutionConfig;
import cd.lan1akea.core.context.RuntimeContext;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.hook.impl.LoggingHook;
import cd.lan1akea.core.message.*;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.model.openai.OpenAIChatModel;
import cd.lan1akea.core.session.Session;
import cd.lan1akea.core.session.SessionState;
import cd.lan1akea.core.state.AgentState;
import cd.lan1akea.core.state.InMemoryAgentStateStore;
import cd.lan1akea.core.tool.*;
import cd.lan1akea.core.tool.builtin.CalculatorTool;
import cd.lan1akea.harness.annotation.ToolFunction;
import cd.lan1akea.harness.annotation.ToolParam;
import cd.lan1akea.harness.support.AnnotationToolResolver;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SDK 能力展示测试 —— 模拟业务方使用 SDK 的每一个原子场景。
 * <p>
 * 每个测试方法只做一件事，粒度细，覆盖 SDK 全部公开 API 设计细节。
 * </p>
 */
@DisplayName("SDK 业务方调用能力展示")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SdkCapabilityShowcaseTest {

    // ========================================================================
    // 共享 fixture
    // ========================================================================

    private StubModel stubModel;

    @BeforeEach
    void setUp() {
        stubModel = new StubModel();
    }

    // ========================================================================
    // 一、工具定义 —— 四种方式
    // ========================================================================

    // --- 方式1：直接实现 Tool 接口 ---

    @Test
    @Order(1)
    @DisplayName("方式1：业务方直接实现 Tool 接口定义工具")
    void toolDefinition_implementToolInterface() {
        // 业务方最底层的能力：直接实现 Tool 接口，完全掌控 name/description/schema/execute
        Tool weatherTool = new Tool() {
            @Override public String getName() { return "get_weather"; }
            @Override public String getDescription() { return "查询指定城市的天气"; }

            @Override
            public ToolSchema getParameters() {
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("city", Map.of("type", "string", "description", "城市名称"));
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "object");
                schema.put("properties", props);
                schema.put("required", List.of("city"));
                return new ToolSchema("get_weather", "查询指定城市的天气", schema);
            }

            @Override
            public Mono<ToolResult> execute(ToolCallParam params) {
                String city = params.getString("city");
                return Mono.just(ToolResult.success(city + ": 晴, 22°C"));
            }
        };

        assertEquals("get_weather", weatherTool.getName());
        assertNotNull(weatherTool.getParameters());
        assertTrue(weatherTool.getParameters().getParametersSchema().toString().contains("city"));

        // 执行工具
        ToolResult result = weatherTool.execute(
            new ToolCallParam("c1", "get_weather", "{\"city\": \"北京\"}")).block();
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("北京"));
    }

    // --- 方式2：继承 ToolBase ---

    @Test
    @Order(2)
    @DisplayName("方式2：业务方继承 ToolBase 基类定义工具")
    void toolDefinition_extendToolBase() {
        // 继承 ToolBase 比直接实现 Tool 接口更省事：参数声明式，Schema 自动生成，校验自动
        ToolBaseWeather tool = new ToolBaseWeather();
        assertEquals("get_weather_v2", tool.getName());
        assertEquals("查询天气（ToolBase方式）", tool.getDescription());

        // Schema 由基类根据 declareParam 自动生成
        ToolSchema schema = tool.getParameters();
        assertNotNull(schema);
        String schemaStr = schema.getParametersSchema().toString();
        assertTrue(schemaStr.contains("city"));
        assertTrue(schemaStr.contains("unit"));

        // 执行
        ToolResult result = tool.execute(
            new ToolCallParam("c1", "get_weather_v2", "{\"city\": \"上海\", \"unit\": \"fahrenheit\"}")).block();
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("上海") && result.getContent().contains("F"));
    }

    /** 业务方自定义：继承 ToolBase */
    static class ToolBaseWeather extends ToolBase {
        ToolBaseWeather() {
            declareStringParam("city", "城市名称", true);
            declareStringParam("unit", "温度单位 (celsius/fahrenheit)", false);
        }

        @Override public String getName() { return "get_weather_v2"; }
        @Override public String getDescription() { return "查询天气（ToolBase方式）"; }

        @Override
        public Mono<ToolResult> execute(ToolCallParam params) {
            String city = params.getString("city");
            String unit = params.getString("unit");
            if (unit == null) unit = "celsius";
            return Mono.just(ToolResult.success(city + ": 晴, 22°" + ("fahrenheit".equals(unit) ? "F" : "C")));
        }
    }

    // --- 方式3：@ToolFunction 标注在类上 ---

    @Test
    @Order(3)
    @DisplayName("方式3：业务方用 @ToolFunction 标注类级别定义工具")
    void toolDefinition_annotatedClass() {
        // 最简洁的方式：POJO + @ToolFunction 类级别注解
        // SDK 自动取第一个 public 方法作为工具执行体
        SearchTool pojo = new SearchTool();

        AnnotationToolResolver resolver = new AnnotationToolResolver();
        assertTrue(resolver.canResolve(pojo));

        Tool tool = resolver.resolve(pojo);
        assertEquals("search_docs", tool.getName());
        assertEquals("全文搜索文档", tool.getDescription());
        assertTrue(tool.getParameters().getParametersSchema().toString().contains("keyword"));

        ToolResult result = tool.execute(
            new ToolCallParam("c1", "search_docs", "{\"keyword\": \"Java反射\"}")).block();
        assertTrue(result.getContent().contains("Java反射"));
    }

    @ToolFunction(name = "search_docs", description = "全文搜索文档")
    static class SearchTool {
        public String search(
            @ToolParam(name = "keyword", description = "搜索关键词", required = true) String keyword,
            @ToolParam(name = "limit", description = "返回条数", defaultValue = "10") int limit) {
            return "找到 " + limit + " 条关于 '" + keyword + "' 的结果";
        }
    }

    // --- 方式4：@ToolFunction 标注在方法上 ---

    @Test
    @Order(4)
    @DisplayName("方式4：业务方用 @ToolFunction 标注方法级别定义工具")
    void toolDefinition_annotatedMethod() {
        // 一个类里可以有多个 @ToolFunction 方法 → 每个方法解析为一个 Tool
        // 注：当前 AnnotationToolResolver 解析第一个找到的 @ToolFunction 方法
        TranslateUtil util = new TranslateUtil();

        AnnotationToolResolver resolver = new AnnotationToolResolver();
        assertTrue(resolver.canResolve(util));

        Tool tool = resolver.resolve(util);
        assertEquals("translate_text", tool.getName());

        ToolResult result = tool.execute(
            new ToolCallParam("c1", "translate_text",
                "{\"text\": \"Hello World\", \"source\": \"en\", \"target\": \"zh\"}")).block();
        assertTrue(result.getContent().contains("Hello World"));
    }

    static class TranslateUtil {
        @ToolFunction(name = "translate_text", description = "翻译文本")
        public String translate(
            @ToolParam(name = "text", description = "待翻译文本", required = true) String text,
            @ToolParam(name = "source", description = "源语言", defaultValue = "auto") String source,
            @ToolParam(name = "target", description = "目标语言", required = true) String target) {
            return "[" + source + "→" + target + "] " + text;
        }
    }

    // ========================================================================
    // 二、ToolResolver SPI —— 核心层的转换契约
    // ========================================================================

    @Test
    @Order(5)
    @DisplayName("ToolResolver: canResolve 识别 @ToolFunction 注解对象")
    void resolver_canResolveDetectsAnnotation() {
        AnnotationToolResolver resolver = new AnnotationToolResolver();

        // 有 @ToolFunction 注解 → true
        assertTrue(resolver.canResolve(new SearchTool()));
        assertTrue(resolver.canResolve(new TranslateUtil()));

        // 普通 POJO → false
        assertFalse(resolver.canResolve(new Object()));
        assertFalse(resolver.canResolve("a string"));

        // CalculatorTool 实现了 Tool 接口但没有 @ToolFunction → false
        // （它应该走 instanceof Tool 分支，不走 resolver）
        assertFalse(resolver.canResolve(new CalculatorTool()));
    }

    @Test
    @Order(6)
    @DisplayName("ToolResolver: resolve 将注解 POJO 反射转换为 Tool")
    void resolver_resolveConvertsToTool() {
        AnnotationToolResolver resolver = new AnnotationToolResolver();

        // 类级 @ToolFunction：取第一个 public 方法
        Tool tool1 = resolver.resolve(new SearchTool());
        assertEquals(SearchTool.class.getSimpleName(), "SearchTool");
        assertTrue(tool1 instanceof Tool);
        assertEquals("search_docs", tool1.getName());

        // 方法级 @ToolFunction：取标注了 @ToolFunction 的方法
        Tool tool2 = resolver.resolve(new TranslateUtil());
        assertEquals("translate_text", tool2.getName());
    }

    @Test
    @Order(7)
    @DisplayName("ToolResolver: 参数元数据从 @ToolParam 注解读取并生成 JSON Schema")
    void resolver_extractsParamMetadataToSchema() {
        AnnotationToolResolver resolver = new AnnotationToolResolver();
        Tool tool = resolver.resolve(new SearchTool());
        ToolSchema schema = tool.getParameters();

        Map<String, Object> raw = schema.getParametersSchema();
        assertEquals("object", raw.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) raw.get("properties");
        assertNotNull(props);

        @SuppressWarnings("unchecked")
        Map<String, Object> keywordProp = (Map<String, Object>) props.get("keyword");
        assertEquals("string", keywordProp.get("type"));
        assertEquals("搜索关键词", keywordProp.get("description"));

        @SuppressWarnings("unchecked")
        Map<String, Object> limitProp = (Map<String, Object>) props.get("limit");
        assertEquals("integer", limitProp.get("type"));
        assertEquals("10", limitProp.get("default"));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) raw.get("required");
        assertNotNull(required);
        assertTrue(required.contains("keyword"));
        assertFalse(required.contains("limit"));
    }

    // ========================================================================
    // 三、ToolRegistry 统一入口 —— registerTool(Object)
    // ========================================================================

    @Test
    @Order(8)
    @DisplayName("ToolRegistry: registerTool(Tool实例) 直接注册")
    void registry_registerToolWithToolInstance() {
        ToolRegistry registry = new ToolRegistry();
        registry.addResolver(new AnnotationToolResolver());

        Tool registered = registry.registerTool(new ToolBaseWeather());
        assertEquals("get_weather_v2", registered.getName());
        assertTrue(registry.contains("get_weather_v2"));
        assertEquals(1, registry.size());
    }

    @Test
    @Order(9)
    @DisplayName("ToolRegistry: registerTool(注解POJO) 走 Resolver convert 后注册")
    void registry_registerToolWithAnnotatedPojo() {
        ToolRegistry registry = new ToolRegistry();
        registry.addResolver(new AnnotationToolResolver());

        // 传入的只是一个普通 POJO，SDK 内部走 Resolver → 反射 → 注册
        Tool registered = registry.registerTool(new SearchTool());
        assertEquals("search_docs", registered.getName());
        assertTrue(registry.contains("search_docs"));
    }

    @Test
    @Order(10)
    @DisplayName("ToolRegistry: registerTool(无法识别的对象) 抛异常")
    void registry_registerToolRejectsUnknown() {
        ToolRegistry registry = new ToolRegistry();
        registry.addResolver(new AnnotationToolResolver());

        assertThrows(IllegalArgumentException.class,
            () -> registry.registerTool(new Object()));
        assertThrows(IllegalArgumentException.class,
            () -> registry.registerTool("not a tool"));
    }

    @Test
    @Order(11)
    @DisplayName("ToolRegistry: registerToolForTenant 租户隔离注册")
    void registry_registerToolForTenant() {
        ToolRegistry registry = new ToolRegistry();
        registry.addResolver(new AnnotationToolResolver());

        // 租户A 注册专属工具
        registry.registerToolForTenant("tenant_a", new SearchTool());
        // 租户B 注册专属工具
        registry.registerToolForTenant("tenant_b", new TranslateUtil());

        // 各租户独立可见
        assertEquals(1, registry.tenantToolCount("tenant_a"));
        assertEquals(1, registry.tenantToolCount("tenant_b"));

        // 全局没有这些工具
        assertFalse(registry.contains("search_docs"));
        assertFalse(registry.contains("translate_text"));

        // 租户A 只能看到自己的
        assertNotNull(registry.getForTenant("tenant_a", "search_docs"));
        assertNull(registry.getForTenant("tenant_a", "translate_text"));

        // 租户B 只能看到自己的
        assertNotNull(registry.getForTenant("tenant_b", "translate_text"));
        assertNull(registry.getForTenant("tenant_b", "search_docs"));
    }

    @Test
    @Order(12)
    @DisplayName("ToolRegistry: 租户工具覆盖同名全局工具")
    void registry_tenantOverridesGlobal() {
        ToolRegistry registry = new ToolRegistry();

        // 全局注册
        registry.register(new ToolBaseWeather());
        // 租户A 注册同名覆盖
        ToolBaseWeather tenantOverride = new ToolBaseWeather() {
            @Override public String getName() { return "get_weather_v2"; }
            @Override public String getDescription() { return "租户A专属天气查询"; }
        };
        registry.registerForTenant("tenant_a", tenantOverride);

        // 全局看到的是原版
        Tool global = registry.getForTenant(null, "get_weather_v2");
        assertEquals("查询天气（ToolBase方式）", global.getDescription());

        // 租户A 看到的是覆盖版
        Tool tenantA = registry.getForTenant("tenant_a", "get_weather_v2");
        assertEquals("租户A专属天气查询", tenantA.getDescription());
    }

    @Test
    @Order(13)
    @DisplayName("ToolRegistry: 无 Resolver 时注解 POJO 注册失败")
    void registry_missingResolverFailsGracefully() {
        ToolRegistry registry = new ToolRegistry();
        // 没有 addResolver → 无法解析注解 POJO

        // Tool 实例仍然能正常注册
        registry.registerTool(new CalculatorTool());
        assertTrue(registry.contains("calculator"));

        // 注解 POJO 注册失败
        assertThrows(IllegalArgumentException.class,
            () -> registry.registerTool(new SearchTool()));
    }

    @Test
    @Order(14)
    @DisplayName("ToolRegistry: register/registerAll/registerForTenant 原有 API 保持兼容")
    void registry_backwardCompatibleApis() {
        ToolRegistry registry = new ToolRegistry();

        // 老 API 仍然可用
        registry.register(new CalculatorTool());
        registry.registerAll(List.of(new ToolBaseWeather()));
        registry.registerForTenant("tenant_x", new CalculatorTool());

        assertTrue(registry.contains("calculator"));
        assertTrue(registry.contains("get_weather_v2"));
        assertEquals(2, registry.size());
    }

    // ========================================================================
    // 四、Hook 体系
    // ========================================================================

    @Test
    @Order(15)
    @DisplayName("Hook: 直接实现 Hook 接口")
    void hook_directImplementation() {
        // 最灵活的方式：直接实现 Hook 接口
        Hook hook = new Hook() {
            @Override public String getName() { return "CustomHook"; }
            @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(HookEventType.PRE_TOOL_CALL); }
            @Override public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
                return Mono.just(HookResult.continue_());
            }
        };

        assertEquals("CustomHook", hook.getName());
        assertTrue(hook.getSubscribedEventTypes().contains(HookEventType.PRE_TOOL_CALL));
        assertEquals(100, hook.getPriority()); // 默认优先级
        assertTrue(hook.isEnabled());
    }

    @Test
    @Order(16)
    @DisplayName("Hook: 直接实现 Hook 接口并声明订阅事件")
    void hook_abstractHookWithAnnotation() {
        // 直接实现 Hook 接口，重写 getSubscribedEventTypes/getPriority
        MyAuditHook hook = new MyAuditHook();

        assertEquals("MyAudit", hook.getName());
        assertEquals(50, hook.getPriority());
        assertTrue(hook.getSubscribedEventTypes().contains(HookEventType.PRE_TOOL_CALL));
        assertTrue(hook.getSubscribedEventTypes().contains(HookEventType.POST_TOOL_CALL));

        // 执行业务逻辑
        HookResult result = hook.onEvent(
            new HookEvent(HookEventType.PRE_TOOL_CALL),
            new HookContext("test", null, null, null, 0, List.of(), null)).block();

        assertEquals(HookResult.ResultType.CONTINUE, result.getResultType());
        assertEquals(1, hook.callCount);
    }

    static class MyAuditHook implements Hook {
        int callCount;
        @Override public String getName() { return "MyAudit"; }
        @Override public Set<HookEventType> getSubscribedEventTypes() {
            return Set.of(HookEventType.PRE_TOOL_CALL, HookEventType.POST_TOOL_CALL);
        }
        @Override public int getPriority() { return 50; }
        @Override public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
            callCount++;
            return Mono.just(HookResult.continue_());
        }
    }

    @Test
    @Order(17)
    @DisplayName("Hook: 通过 ABORT 结果阻止后续执行")
    void hook_abortStopsChain() {
        HookChain chain = new HookChain();
        List<String> executionOrder = new ArrayList<>();

        chain.register(new Hook() {
            @Override public String getName() { return "Blocker"; }
            @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(HookEventType.PRE_TOOL_CALL); }
            @Override public int getPriority() { return 1; }
            @Override public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
                executionOrder.add("blocker");
                return Mono.just(HookResult.abort("测试阻止"));
            }
        });

        chain.register(new Hook() {
            @Override public String getName() { return "ShouldNotRun"; }
            @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(HookEventType.PRE_TOOL_CALL); }
            @Override public int getPriority() { return 2; }
            @Override public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
                executionOrder.add("shouldNotRun");
                return Mono.just(HookResult.continue_());
            }
        });

        HookResult result = chain.fire(HookEventType.PRE_TOOL_CALL,
            new HookEvent(HookEventType.PRE_TOOL_CALL),
            new HookContext("test", null, null, null, 0, List.of(), null)).block();

        assertTrue(result.isAbort());
        assertEquals(List.of("blocker"), executionOrder); // 第二个 Hook 没被执行
    }

    @Test
    @Order(18)
    @DisplayName("Hook: 通过 MODIFY 结果修改事件数据")
    void hook_modifyTransformsEvent() {
        HookChain chain = new HookChain();

        // Hook 修改 PRE_REASONING 事件中的消息列表
        chain.register(new Hook() {
            @Override public String getName() { return "Injector"; }
            @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(HookEventType.PRE_REASONING); }
            @Override public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
                if (event instanceof ReasoningEvent re) {
                    re.getMessages().add(SystemMessage.of("注入的额外指令"));
                    return Mono.just(HookResult.modify(re));
                }
                return Mono.just(HookResult.continue_());
            }
        });

        ReasoningEvent event = new ReasoningEvent(HookEventType.PRE_REASONING);
        List<Msg> messages = new ArrayList<>();
        messages.add(UserMessage.of("你好"));
        event.setMessages(messages);

        HookResult result = chain.fire(HookEventType.PRE_REASONING, event,
            new HookContext("test", null, null, null, 0, List.of(), null)).block();

        assertTrue(result.isModify());
        assertEquals(2, event.getMessages().size());
        assertEquals("注入的额外指令", event.getMessages().get(1).getTextContent());
    }

    @Test
    @Order(19)
    @DisplayName("Hook: Hook 链按 priority 排序执行")
    void hook_chainOrdersByPriority() {
        HookChain chain = new HookChain();
        List<String> order = new ArrayList<>();

        chain.register(new Hook() {
            @Override public String getName() { return "Third"; }
            @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(HookEventType.PRE_TOOL_CALL); }
            @Override public int getPriority() { return 300; }
            @Override public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
                order.add("third");
                return Mono.just(HookResult.continue_());
            }
        });
        chain.register(new Hook() {
            @Override public String getName() { return "First"; }
            @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(HookEventType.PRE_TOOL_CALL); }
            @Override public int getPriority() { return 10; }
            @Override public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
                order.add("first");
                return Mono.just(HookResult.continue_());
            }
        });
        chain.register(new Hook() {
            @Override public String getName() { return "Second"; }
            @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(HookEventType.PRE_TOOL_CALL); }
            @Override public int getPriority() { return 200; }
            @Override public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
                order.add("second");
                return Mono.just(HookResult.continue_());
            }
        });

        chain.fire(HookEventType.PRE_TOOL_CALL,
            new HookEvent(HookEventType.PRE_TOOL_CALL),
            new HookContext("test", null, null, null, 0, List.of(), null)).block();

        assertEquals(List.of("first", "second", "third"), order);
    }

    // ========================================================================
    // 五、Builder API —— 流式构建 Agent
    // ========================================================================

    @Test
    @Order(20)
    @DisplayName("Builder: 最简构建（name + model）")
    void builder_minimalBuild() {
        HarnessAgent agent = HarnessAgent.builder()
            .name("MinimalAgent")
            .model(stubModel)
            .build();

        assertNotNull(agent);
        assertEquals("MinimalAgent", agent.getName());
        assertTrue(agent.getDelegate().isBuilt());
        assertEquals(0, agent.getDelegate().getToolRegistry().size());
    }

    @Test
    @Order(21)
    @DisplayName("Builder: 注册 Tool 实例和注解 POJO 混合工具")
    void builder_mixedTools() {
        HarnessAgent agent = HarnessAgent.builder()
            .name("MixedAgent")
            .model(stubModel)
            .tool(new CalculatorTool())          // Tool 接口实例
            .tool(new ToolBaseWeather())          // ToolBase 子类
            .tool(new SearchTool())               // @ToolFunction 注解 POJO
            .tools(new TranslateUtil())           // 批量 tools() 同样支持混用
            .build();

        ToolRegistry reg = agent.getDelegate().getToolRegistry();
        assertEquals(4, reg.size());
        assertTrue(reg.contains("calculator"));
        assertTrue(reg.contains("get_weather_v2"));
        assertTrue(reg.contains("search_docs"));
        assertTrue(reg.contains("translate_text"));
    }

    @Test
    @Order(22)
    @DisplayName("Builder: 完整构建（所有配置项）")
    void builder_fullConfiguration() {
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();

        HarnessAgent agent = HarnessAgent.builder()
            .name("FullAgent")
            .model(new OpenAIChatModel("sk-test", "gpt-4o"))
            .tool(new CalculatorTool())
            .hook(new LoggingHook("FullLogger"))
            .stateStore(stateStore)
            .executionConfig(AgentExecutionConfig.builder()
                .maxIterations(15)
                .temperature(0.3)
                .maxTokens(8192)
                .totalTimeoutMs(60000)
                .build())
            .build();

        assertNotNull(agent);
        assertEquals("FullAgent", agent.getName());
        assertTrue(agent.getDelegate().isBuilt());
        assertNotNull(agent.getDelegate().getStateStore());
        assertEquals(1, agent.getDelegate().getToolRegistry().size());
        assertEquals(1, agent.getDelegate().getHookChain().size());
    }


    // ========================================================================
    // 六、ToolExecutor 工具执行器
    // ========================================================================

    @Test
    @Order(24)
    @DisplayName("ToolExecutor: 正常执行工具")
    void executor_successfulExecution() {
        ToolRegistry registry = new ToolRegistry();
        registry.addResolver(new AnnotationToolResolver());
        registry.registerTool(new SearchTool());

        ToolExecutor executor = new ToolExecutor(registry);
        ToolResult result = executor.execute(
            new ToolCallParam("c1", "search_docs", "{\"keyword\": \"test\"}")).block();

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("test"));
    }

    @Test
    @Order(25)
    @DisplayName("ToolExecutor: 工具不存在时返回失败")
    void executor_toolNotFoundReturnsFailure() {
        ToolExecutor executor = new ToolExecutor(new ToolRegistry());
        ToolResult result = executor.execute(
            new ToolCallParam("c1", "nonexistent", "{}")).block();

        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("不存在"));
    }

    @Test
    @Order(26)
    @DisplayName("ToolExecutor: 租户感知查找")
    void executor_tenantAwareLookup() {
        ToolRegistry registry = new ToolRegistry();
        registry.addResolver(new AnnotationToolResolver());
        registry.registerToolForTenant("tenant_a", new SearchTool());

        ToolExecutor executor = new ToolExecutor(registry);

        // 全局查找 → 找不到
        ToolResult r1 = executor.execute(
            new ToolCallParam("c1", "search_docs", "{\"keyword\": \"x\"}"), null).block();
        assertFalse(r1.isSuccess());

        // 租户A 查找 → 找到
        ToolResult r2 = executor.execute(
            new ToolCallParam("c2", "search_docs", "{\"keyword\": \"x\"}"), "tenant_a").block();
        assertTrue(r2.isSuccess());
    }

    // ========================================================================
    // 七、Agent 对话执行 (Stub 模型)
    // ========================================================================

    @Test
    @Order(27)
    @DisplayName("Agent: 基础对话")
    void agent_basicChat() {
        stubModel.willRespond("你好，我是测试助手");

        HarnessAgent agent = quickAgent("ChatAgent");
        ChatResponse resp = agent.chat(List.of(UserMessage.of("你好"))).block();

        assertNotNull(resp);
        assertEquals("你好，我是测试助手", resp.getMessage().getTextContent());
    }

    @Test
    @Order(28)
    @DisplayName("Agent: 工具调用链 — LLM 返回 tool_calls → 执行工具 → 回结果")
    void agent_toolCallingChain() {
        // LLM 返回包含工具调用的响应
        Msg toolCallMsg = AssistantMessage.builder()
            .addToolUse("call_1", "calculator", "{\"expression\": \"2+3\"}")
            .build();
        stubModel.setResponse(new ChatResponse(toolCallMsg, new ChatUsage(10, 5), "tool_calls", null));

        ToolRegistry registry = new ToolRegistry();
        registry.register(new CalculatorTool());

        HarnessAgent agent = buildAgent("ToolAgent", registry, new HookChain());
        ChatResponse resp = agent.chat(List.of(UserMessage.of("2+3等于几？"))).block();

        assertNotNull(resp);
        assertTrue(resp.getMessage().hasToolCalls() || "tool_calls".equals(resp.getFinishReason()));
    }

    @Test
    @Order(29)
    @DisplayName("Agent: 流式响应")
    void agent_streaming() {
        HarnessAgent agent = quickAgent("StreamAgent");
        List<ChatStreamChunk> chunks = agent.stream(List.of(UserMessage.of("hi")))
            .collectList().block();

        assertNotNull(chunks);
        // Stub 模型不输出 chunk，验证流式调用不抛异常即可
    }

    @Test
    @Order(30)
    @DisplayName("Agent: 带 RuntimeContext 传递租户/用户/会话信息")
    void agent_runtimeContextPropagation() {
        stubModel.willRespond("ok");

        HarnessAgent agent = quickAgent("CtxAgent");
        RuntimeContext ctx = RuntimeContext.builder()
            .tenantId("tenant_1").userId("user_1").sessionId("sess_1").build();

        ChatResponse resp = agent.chat(List.of(UserMessage.of("hello")), ctx).block();
        assertNotNull(resp);
        assertEquals("ok", resp.getMessage().getTextContent());
    }

    // ========================================================================
    // 八、Agent 生命周期
    // ========================================================================

    @Test
    @Order(31)
    @DisplayName("Agent: build 后 isBuilt=true，重复 build 抛异常")
    void agent_doubleBuildFails() {
        HarnessAgent agent = quickAgent("LifecycleAgent");
        assertTrue(agent.getDelegate().isBuilt());

        // 已构建的 Agent 再次 build 会抛异常
        assertThrows(Exception.class, () -> agent.getDelegate().build().block());
    }

    @Test
    @Order(32)
    @DisplayName("Agent: interrupt 中断正在执行的请求")
    void agent_interruptDoesNotThrow() {
        HarnessAgent agent = quickAgent("InterruptAgent");
        // interrupt 在不执行时调用也不应抛异常
        assertDoesNotThrow(() -> agent.interrupt());
    }

    @Test
    @Order(33)
    @DisplayName("Agent: shutdown 优雅关闭")
    void agent_shutdown() {
        HarnessAgent agent = quickAgent("ShutdownAgent");
        agent.shutdown().block();
        assertFalse(agent.getDelegate().isBuilt());
    }

    // ========================================================================
    // 九、Hook + Agent 集成
    // ========================================================================

    @Test
    @Order(34)
    @DisplayName("集成: PreCall Hook 在对话开始前触发")
    void integration_preCallHookFiresBeforeChat() {
        stubModel.willRespond("got it");

        List<String> hookEvents = new ArrayList<>();
        HookChain chain = new HookChain();
        chain.register(new Hook() {
            @Override public String getName() { return "PreCallWatcher"; }
            @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(HookEventType.PRE_CALL); }
            @Override public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
                hookEvents.add("pre_call");
                return Mono.just(HookResult.continue_());
            }
        });

        HarnessAgent agent = buildAgent("HookAgent", new ToolRegistry(), chain);
        agent.chat(List.of(UserMessage.of("hi"))).block();

        assertEquals(1, hookEvents.size());
        assertEquals("pre_call", hookEvents.get(0));
    }

    @Test
    @Order(35)
    @DisplayName("集成: 自定义 Hook 可与内置 Hook 混用")
    void integration_annotatedAndManualHooksTogether() {
        stubModel.willRespond("ok");

        HookChain chain = new HookChain();
        chain.register(new MyAuditHook());          // 注解驱动
        chain.register(new LoggingHook("Manual"));  // 手动注册

        HarnessAgent agent = buildAgent("MixedHookAgent", new ToolRegistry(), chain);
        ChatResponse resp = agent.chat(List.of(UserMessage.of("test"))).block();

        assertNotNull(resp);
    }


    // ========================================================================
    // 十一、AgentState / Session 持久化
    // ========================================================================

    @Test
    @Order(37)
    @DisplayName("Session: openSession 创建新会话")
    void session_openSession() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        HarnessAgent agent = HarnessAgent.builder()
            .name("SessionAgent")
            .model(stubModel)
            .stateStore(store)
            .build();

        Session session = agent.getDelegate().openSession("my-session").block();
        assertNotNull(session);
        assertEquals("my-session", session.getId().getValue());
        assertEquals(SessionState.ACTIVE, session.getState());
    }

    @Test
    @Order(38)
    @DisplayName("AgentState: saveCheckpoint + loadLatestCheckpoint 检查点保存与恢复")
    void checkpoint_saveAndRestore() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        HarnessAgent agent = HarnessAgent.builder()
            .name("CheckpointAgent")
            .model(stubModel)
            .stateStore(store)
            .build();

        // 打开会话
        agent.getDelegate().openSession("sess-checkpoint").block();

        // 保存检查点
        AgentState checkpoint = new AgentState("CheckpointAgent", "sess-checkpoint",
            3, List.of(UserMessage.of("hello"), AssistantMessage.of("hi")),
            Map.of(), 150, false, null, System.currentTimeMillis());
        store.saveCheckpoint(checkpoint).block();

        // 恢复
        AgentState restored = store.loadLatestCheckpoint("sess-checkpoint").block();
        assertNotNull(restored);
        assertEquals(3, restored.getIteration());
        assertEquals(150, restored.getTotalTokens());
    }

    // ========================================================================
    // 十二、边界情况
    // ========================================================================

    @Test
    @Order(39)
    @DisplayName("边界: 空 ToolRegistry 注册")
    void edge_emptyRegistry() {
        ToolRegistry registry = new ToolRegistry();
        assertEquals(0, registry.size());
        assertTrue(registry.getToolNames().isEmpty());
    }

    @Test
    @Order(40)
    @DisplayName("边界: HookChain 无 Hook 时 fire 返回 CONTINUE")
    void edge_emptyHookChain() {
        HookChain chain = new HookChain();
        assertEquals(0, chain.size());

        HookResult result = chain.fire(HookEventType.PRE_TOOL_CALL,
            new HookEvent(HookEventType.PRE_TOOL_CALL),
            new HookContext("test", null, null, null, 0, List.of(), null)).block();

        assertEquals(HookResult.ResultType.CONTINUE, result.getResultType());
    }

    @Test
    @Order(41)
    @DisplayName("边界: 禁用的 Hook 被跳过")
    void edge_disabledHookSkipped() {
        HookChain chain = new HookChain();
        List<String> executed = new ArrayList<>();

        chain.register(new Hook() {
            @Override public String getName() { return "Disabled"; }
            @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(HookEventType.PRE_TOOL_CALL); }
            @Override public boolean isEnabled() { return false; }
            @Override public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
                executed.add("disabled");
                return Mono.just(HookResult.continue_());
            }
        });
        chain.register(new Hook() {
            @Override public String getName() { return "Enabled"; }
            @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(HookEventType.PRE_TOOL_CALL); }
            @Override public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
                executed.add("enabled");
                return Mono.just(HookResult.continue_());
            }
        });

        chain.fire(HookEventType.PRE_TOOL_CALL,
            new HookEvent(HookEventType.PRE_TOOL_CALL),
            new HookContext("test", null, null, null, 0, List.of(), null)).block();

        assertEquals(List.of("enabled"), executed);
    }

    @Test
    @Order(42)
    @DisplayName("边界: unregister 注销 Hook")
    void edge_unregisterHook() {
        HookChain chain = new HookChain();
        chain.register(new LoggingHook("ToRemove"));
        assertEquals(1, chain.size());

        chain.unregister("ToRemove");
        assertEquals(0, chain.size());
    }

    // ========================================================================
    // helpers
    // ========================================================================

    private HarnessAgent quickAgent(String name) {
        return buildAgent(name, new ToolRegistry(), new HookChain());
    }

    private HarnessAgent buildAgent(String name, ToolRegistry toolRegistry, HookChain hookChain) {
        AgentConfig config = AgentConfig.builder()
            .name(name)
            .model(stubModel)
            .toolRegistry(toolRegistry)
            .hookChain(hookChain)
            .stateStore(new InMemoryAgentStateStore())
            .executionConfig(AgentExecutionConfig.builder().maxIterations(3).build())
            .build();

        ReActAgent inner = new ReActAgent(config);
        inner.build().block();
        return new HarnessAgent(inner);
    }

    /** 可编程控制返回内容的 Stub 模型 */
    static class StubModel extends ChatModelBase {
        private ChatResponse response;

        StubModel() {
            super("stub", "stub-model", msgs -> List.of(Map.of("role", "user", "content", "stub")));
        }

        void willRespond(String text) {
            this.response = new ChatResponse(AssistantMessage.of(text),
                new ChatUsage(5, 3), "stop", null);
        }

        void setResponse(ChatResponse r) { this.response = r; }

        @Override protected Map<String, String> buildAuthHeaders() { return Map.of(); }
        @Override protected String buildApiUrl() { return "http://localhost/stub"; }

        @Override
        protected Mono<ChatResponse> doChat(List<Map<String, Object>> messages,
                                            List<ToolSchema> toolSchemas,
                                            GenerateOptions options) {
            return response != null ? Mono.just(response) : Mono.empty();
        }

        @Override
        protected Flux<ChatStreamChunk> doStream(List<Map<String, Object>> messages,
                                                  List<ToolSchema> toolSchemas,
                                                  GenerateOptions options) {
            return Flux.empty();
        }
    }
}
