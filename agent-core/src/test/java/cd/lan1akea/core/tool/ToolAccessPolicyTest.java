package cd.lan1akea.core.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolAccessPolicyTest {

    private ToolAccessPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new ToolAccessPolicy();
    }

    @Test
    void testDefaultAllow() {
        assertTrue(policy.isAllowed("any_tenant", "any_tool"));
    }

    @Test
    void testAllowlistAllowsListed() {
        policy.allow("t1", Set.of("calc", "search"));
        assertTrue(policy.isAllowed("t1", "calc"));
        assertTrue(policy.isAllowed("t1", "search"));
        assertFalse(policy.isAllowed("t1", "weather"));
    }

    @Test
    void testAllowlistEmptyAllowsAll() {
        policy.allow("t1", Set.of());
        assertTrue(policy.isAllowed("t1", "anything"));
    }

    @Test
    void testBlocklistDeniesListed() {
        policy.block("t1", Set.of("dangerous_tool"));
        assertTrue(policy.isAllowed("t1", "safe_tool"));
        assertFalse(policy.isAllowed("t1", "dangerous_tool"));
    }

    @Test
    void testAllowlistTakesPriorityOverBlocklist() {
        policy.allow("t1", Set.of("calc"));
        policy.block("t1", Set.of("calc"));
        // allowlist 优先 → calc 被允许
        assertTrue(policy.isAllowed("t1", "calc"));
        assertFalse(policy.isAllowed("t1", "other"));
    }

    @Test
    void testDifferentTenantsIndependent() {
        policy.allow("t1", Set.of("calc"));
        policy.block("t2", Set.of("calc"));

        assertTrue(policy.isAllowed("t1", "calc"));
        assertFalse(policy.isAllowed("t2", "calc"));
        assertTrue(policy.isAllowed("t3", "calc")); // 无策略 → 放行
    }

    @Test
    void testRemovePolicy() {
        policy.allow("t1", Set.of("calc"));
        assertFalse(policy.isAllowed("t1", "search"));

        policy.remove("t1");
        assertTrue(policy.isAllowed("t1", "search"));
    }

    @Test
    void testClearAll() {
        policy.allow("t1", Set.of("calc"));
        policy.block("t2", Set.of("bad"));

        policy.clear();
        assertTrue(policy.isAllowed("t1", "calc"));
        assertTrue(policy.isAllowed("t2", "bad"));
    }

    @Test
    void testGetAllowlist() {
        policy.allow("t1", Set.of("a", "b"));
        assertEquals(2, policy.getAllowlist("t1").size());
        assertTrue(policy.getAllowlist("nonexistent").isEmpty());
    }

    @Test
    void testGetBlocklist() {
        policy.block("t1", Set.of("x"));
        assertEquals(1, policy.getBlocklist("t1").size());
        assertTrue(policy.getBlocklist("nonexistent").isEmpty());
    }
}
