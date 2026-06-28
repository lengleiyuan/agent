package cd.lan1akea.core.model;

import cd.lan1akea.core.model.dashscope.DashScopeChatModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DashScopeChatModelTest {

    @Test
    void testProviderAndModelName() {
        DashScopeChatModel m = new DashScopeChatModel("sk-test", "qwen-plus");
        assertEquals("dashscope", m.getProvider());
        assertEquals("qwen-plus", m.getModelName());
    }

    @Test
    void testDefaultBaseUrl() {
        DashScopeChatModel m = new DashScopeChatModel("sk-test", "qwen-turbo");
        // 默认使用兼容模式 endpoint
        assertEquals(128_000, m.getMaxInputTokens());
        assertTrue(m.supportsStreaming());
        assertTrue(m.supportsToolCalling());
    }

    @Test
    void testCustomBaseUrl() {
        DashScopeChatModel m = new DashScopeChatModel("sk-test", "custom-model", "https://custom.api.com/v1");
        assertEquals("dashscope", m.getProvider());
        assertEquals("custom-model", m.getModelName());
    }

    @Test
    void testDefaults() {
        DashScopeChatModel m = new DashScopeChatModel("sk-test", "qwen-max");
        assertEquals(4096, m.getDefaultMaxTokens());
        assertEquals(0.7, m.getDefaultTemperature(), 0.001);
    }
}
