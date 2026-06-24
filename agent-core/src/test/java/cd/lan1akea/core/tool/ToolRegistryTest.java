package cd.lan1akea.core.tool;

import cd.lan1akea.core.model.ToolSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
    }

    @Test
    void testRegisterGlobalTool() {
        registry.register(new StubTool("echo"));
        assertTrue(registry.contains("echo"));
        assertEquals(1, registry.size());
    }

    @Test
    void testRegisterToolObject() {
        registry.registerTool(new StubTool("calc"));
        assertTrue(registry.contains("calc"));
    }

    @Test
    void testRegisterNonToolObjectThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            registry.registerTool("not a tool"));
    }

    @Test
    void testGetGlobalTool() {
        registry.register(new StubTool("search"));
        Tool t = registry.get("search");
        assertNotNull(t);
        assertEquals("search", t.getName());
    }

    @Test
    void testGetNonExistentTool() {
        assertNull(registry.get("nonexistent"));
    }

    @Test
    void testUnregisterGlobalTool() {
        registry.register(new StubTool("temp"));
        registry.unregister("temp");
        assertFalse(registry.contains("temp"));
    }

    @Test
    void testRegisterForTenant() {
        StubTool tenantTool = new StubTool("tenant_echo");
        registry.registerForTenant("tenant_A", tenantTool);

        assertEquals(1, registry.tenantToolCount("tenant_A"));
        assertEquals(0, registry.tenantToolCount("tenant_B"));
    }

    @Test
    void testTenantToolOverridesGlobal() {
        registry.register(new StubTool("shared"));
        registry.registerForTenant("tenant_A", new StubTool("shared", "tenant version"));

        Tool global = registry.get("shared");
        Tool tenant = registry.getForTenant("tenant_A", "shared");

        assertEquals("global", global.getDescription());
        assertEquals("tenant version", tenant.getDescription());
    }

    @Test
    void testGetForTenantFallsBackToGlobal() {
        registry.register(new StubTool("global_only"));

        Tool t = registry.getForTenant("tenant_X", "global_only");
        assertNotNull(t);
        assertEquals("global_only", t.getName());
    }

    @Test
    void testGetForTenantNullReturnsGlobal() {
        registry.register(new StubTool("g"));

        Tool t = registry.getForTenant(null, "g");
        assertNotNull(t);
    }

    @Test
    void testGetSchemasForTenantMergesGlobalAndTenant() {
        registry.register(new StubTool("global_tool"));
        registry.registerForTenant("tenant_A", new StubTool("tenant_tool"));

        List<ToolSchema> schemas = registry.getSchemasForTenant("tenant_A");
        assertTrue(schemas.size() >= 2);
        assertTrue(schemas.stream().anyMatch(s -> s.getName().equals("global_tool")));
        assertTrue(schemas.stream().anyMatch(s -> s.getName().equals("tenant_tool")));
    }

    @Test
    void testGetSchemasForTenantNullReturnsGlobalOnly() {
        registry.register(new StubTool("global_tool"));
        registry.registerForTenant("tenant_A", new StubTool("tenant_tool"));

        List<ToolSchema> schemas = registry.getSchemasForTenant(null);
        assertEquals(1, schemas.size());
        assertEquals("global_tool", schemas.get(0).getName());
    }

    @Test
    void testGetSchemasForTenantWithOverride() {
        registry.register(new StubTool("dup", "global"));
        registry.registerForTenant("tenant_A", new StubTool("dup", "tenant"));

        List<ToolSchema> schemas = registry.getSchemasForTenant("tenant_A");
        long count = schemas.stream().filter(s -> s.getName().equals("dup")).count();
        assertEquals(1, count, "同名工具应只出现一次");
    }

    @Test
    void testUnregisterForTenant() {
        registry.registerForTenant("tenant_A", new StubTool("t"));
        assertEquals(1, registry.tenantToolCount("tenant_A"));

        registry.unregisterForTenant("tenant_A", "t");
        assertEquals(0, registry.tenantToolCount("tenant_A"));
    }

    @Test
    void testUnregisterForTenantNonExistent() {
        registry.unregisterForTenant("no_tenant", "no_tool");
        assertEquals(0, registry.tenantToolCount("no_tenant"));
    }

    @Test
    void testGetToolNames() {
        registry.register(new StubTool("a"));
        registry.register(new StubTool("b"));
        List<String> names = registry.getToolNames();
        assertEquals(2, names.size());
        assertTrue(names.contains("a"));
        assertTrue(names.contains("b"));
    }

    @Test
    void testGetAllTools() {
        registry.register(new StubTool("a"));
        registry.register(new StubTool("b"));
        assertEquals(2, registry.getAllTools().size());
    }

    @Test
    void testRegisterAll() {
        registry.registerAll(List.of(new StubTool("a"), new StubTool("b"), new StubTool("c")));
        assertEquals(3, registry.size());
    }

    // ========================================================================
    // ToolGroup 集成
    // ========================================================================

    @Test
    void testRegisterGlobalGroup() {
        ToolGroup group = new ToolGroup("global-tools", ToolGroupScope.GLOBAL);
        group.addTool(new StubTool("g1"));
        group.addTool(new StubTool("g2"));
        registry.registerGroup(group);

        assertEquals(2, registry.size());
        assertTrue(registry.contains("g1"));
        assertTrue(registry.contains("g2"));
        assertEquals(1, registry.getGroups().size());
        assertEquals("global-tools", registry.getGroup("global-tools").getName());
    }

    @Test
    void testRegisterTenantGroup() {
        ToolGroup group = new ToolGroup("tenant_A", ToolGroupScope.TENANT);
        group.addTool(new StubTool("t_tool"));
        registry.registerGroup(group);

        // TENANT scope: group name = tenantId
        assertEquals(0, registry.size(), "TENANT工具不在全局注册表");
        assertEquals(1, registry.tenantToolCount("tenant_A"));
        assertNotNull(registry.getForTenant("tenant_A", "t_tool"));
    }

    @Test
    void testRegisterTenantGroupExplicitTenantId() {
        ToolGroup group = new ToolGroup("sales-tools", ToolGroupScope.TENANT);
        group.addTool(new StubTool("crm"));
        registry.registerGroup("tenant_X", null, group);

        assertNotNull(registry.getForTenant("tenant_X", "crm"));
        assertNull(registry.getForTenant("tenant_Y", "crm"));
    }

    @Test
    void testRegisterUserGroup() {
        ToolGroup group = new ToolGroup("user-tools", ToolGroupScope.USER);
        group.addTool(new StubTool("personal_calc"));
        registry.registerGroup("tenant_1", "user_1", group);

        assertEquals(0, registry.size());
        assertEquals(0, registry.tenantToolCount("tenant_1"));
        assertEquals(1, registry.userToolCount("tenant_1", "user_1"));
        assertEquals(0, registry.userToolCount("tenant_1", "user_2"));
    }

    @Test
    void testRegisterSessionGroup() {
        ToolGroup group = new ToolGroup("session-tools", ToolGroupScope.SESSION);
        group.addTool(new StubTool("temp_tool"));
        registry.registerSessionGroup("sess_123", group);

        assertEquals(1, registry.sessionToolCount("sess_123"));
        assertEquals(0, registry.sessionToolCount("sess_456"));
    }

    @Test
    void testGetSchemasWithAllScopes() {
        // GLOBAL
        registry.register(new StubTool("global_calc"));
        // TENANT
        registry.registerForTenant("t1", new StubTool("tenant_search"));
        // USER
        registry.registerForUser("t1", "u1", new StubTool("user_tool"));
        // SESSION
        registry.registerForSession("s1", new StubTool("session_tool"));

        // 仅全局
        List<ToolSchema> schemas = registry.getSchemas(null, null, null);
        assertEquals(1, schemas.size());
        assertEquals("global_calc", schemas.get(0).getName());

        // 全局+租户
        schemas = registry.getSchemas("t1", null, null);
        assertTrue(schemas.size() >= 2);
        assertTrue(schemas.stream().anyMatch(s -> s.getName().equals("tenant_search")));

        // 全局+租户+用户
        schemas = registry.getSchemas("t1", "u1", null);
        assertTrue(schemas.stream().anyMatch(s -> s.getName().equals("user_tool")));

        // 全部
        schemas = registry.getSchemas("t1", "u1", "s1");
        assertTrue(schemas.stream().anyMatch(s -> s.getName().equals("session_tool")));
        assertTrue(schemas.stream().anyMatch(s -> s.getName().equals("global_calc")));
    }

    @Test
    void testGetSchemasUserOverrideTenant() {
        registry.registerForTenant("t1", new StubTool("dup", "tenant_version"));
        registry.registerForUser("t1", "u1", new StubTool("dup", "user_version"));

        List<ToolSchema> schemas = registry.getSchemas("t1", "u1", null);
        long dupCount = schemas.stream().filter(s -> s.getName().equals("dup")).count();
        assertEquals(1, dupCount, "同名工具用户级覆盖租户级");

        Tool tool = registry.getForTenant("t1", "dup");
        assertNotNull(tool);
        assertEquals("tenant_version", tool.getDescription(),
            "getForTenant 不受用户覆盖影响");
    }

    @Test
    void testGroupRetrieval() {
        ToolGroup g1 = new ToolGroup("alpha", ToolGroupScope.GLOBAL);
        g1.addTool(new StubTool("a1"));
        registry.registerGroup(g1);

        ToolGroup g2 = new ToolGroup("beta", ToolGroupScope.TENANT);
        g2.addTool(new StubTool("b1"));
        registry.registerGroup(g2);

        assertEquals(2, registry.getGroups().size());
        assertNotNull(registry.getGroup("alpha"));
        assertNotNull(registry.getGroup("beta"));
        assertNull(registry.getGroup("nonexistent"));
    }

    @Test
    void testTenantToolsIsolationAcrossTenants() {
        registry.registerForTenant("tenant_1", new StubTool("t1"));
        registry.registerForTenant("tenant_2", new StubTool("t2"));

        assertEquals(1, registry.tenantToolCount("tenant_1"));
        assertEquals(1, registry.tenantToolCount("tenant_2"));
        assertNotNull(registry.getForTenant("tenant_1", "t1"));
        assertNull(registry.getForTenant("tenant_1", "t2"));
        assertNull(registry.getForTenant("tenant_2", "t1"));
        assertNotNull(registry.getForTenant("tenant_2", "t2"));
    }

    // ========================================================================
    // Stub
    // ========================================================================

    static class StubTool implements Tool {
        private final String name;
        private final String desc;

        StubTool(String name) { this(name, "global"); }
        StubTool(String name, String desc) { this.name = name; this.desc = desc; }

        @Override public String getName() { return name; }
        @Override public String getDescription() { return desc; }

        @Override
        public ToolSchema getParameters() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", Map.of());
            return new ToolSchema(name, desc, schema);
        }

        @Override public Mono<ToolResult> execute(ToolCallParam params) {
            return Mono.just(ToolResult.success("ok"));
        }
    }
}
