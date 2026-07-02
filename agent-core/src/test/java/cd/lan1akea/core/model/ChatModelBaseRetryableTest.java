package cd.lan1akea.core.model;

import cd.lan1akea.core.formatter.OpenAiMessageFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChatModelBase.isRetryable 完整分支覆盖测试。
 * 验证 P0 改动：IO 异常 / 超时 / cause 链重试判定。
 */
class ChatModelBaseRetryableTest {

    private TestChatModel model;

    @BeforeEach
    void setUp() {
        model = new TestChatModel();
    }

    // ═══════════════════════════════════════════════════════════
    // ModelException — HTTP status 分支
    // ═══════════════════════════════════════════════════════════

    @Test
    void retryableOn429() {
        assertTrue(model.isRetryable(new ModelException("openai", "gpt-4", 429, "rate limited")),
            "429 Too Many Requests should be retryable");
    }

    @Test
    void retryableOn500() {
        assertTrue(model.isRetryable(new ModelException("openai", "gpt-4", 500, "internal error")),
            "500 Internal Server Error should be retryable");
    }

    @Test
    void retryableOn502() {
        assertTrue(model.isRetryable(new ModelException("openai", "gpt-4", 502, "bad gateway")),
            "502 Bad Gateway should be retryable");
    }

    @Test
    void retryableOn503() {
        assertTrue(model.isRetryable(new ModelException("openai", "gpt-4", 503, "service unavailable")),
            "503 Service Unavailable should be retryable");
    }

    @Test
    void notRetryableOn400() {
        assertFalse(model.isRetryable(new ModelException("openai", "gpt-4", 400, "bad request")),
            "400 Bad Request should NOT be retryable");
    }

    @Test
    void notRetryableOn401() {
        assertFalse(model.isRetryable(new ModelException("openai", "gpt-4", 401, "unauthorized")),
            "401 Unauthorized should NOT be retryable");
    }

    @Test
    void notRetryableOn403() {
        assertFalse(model.isRetryable(new ModelException("openai", "gpt-4", 403, "forbidden")),
            "403 Forbidden should NOT be retryable");
    }

    @Test
    void notRetryableOn404() {
        assertFalse(model.isRetryable(new ModelException("openai", "gpt-4", 404, "not found")),
            "404 Not Found should NOT be retryable");
    }

    // ═══════════════════════════════════════════════════════════
    // IOException 分支
    // ═══════════════════════════════════════════════════════════

    @Test
    void retryableOnIOException() {
        assertTrue(model.isRetryable(new IOException("connection reset")),
            "IOException should be retryable");
    }

    @Test
    void retryableOnSocketTimeoutException() {
        assertTrue(model.isRetryable(new SocketTimeoutException("read timed out")),
            "SocketTimeoutException (subclass of IOException) should be retryable");
    }

    // ═══════════════════════════════════════════════════════════
    // TimeoutException 分支
    // ═══════════════════════════════════════════════════════════

    @Test
    void retryableOnTimeoutException() {
        assertTrue(model.isRetryable(new TimeoutException("operation timed out")),
            "TimeoutException should be retryable");
    }

    // ═══════════════════════════════════════════════════════════
    // cause 链遍历
    // ═══════════════════════════════════════════════════════════

    @Test
    void retryableOnIOExceptionInCauseChain() {
        RuntimeException wrapper = new RuntimeException("wrapper",
            new IOException("inner IO error"));
        assertTrue(model.isRetryable(wrapper),
            "IOException in cause chain should be retryable");
    }

    @Test
    void retryableOnTimeoutInCauseChain() {
        RuntimeException deep = new RuntimeException("wrapped timeout",
            new TimeoutException("timed out"));
        assertTrue(model.isRetryable(deep),
            "TimeoutException in cause chain should be retryable");
    }

    @Test
    void retryableOnDeepCauseChain() {
        RuntimeException level1 = new RuntimeException("L1",
            new IllegalStateException("L2",
                new IOException("L3 - connection refused")));
        assertTrue(model.isRetryable(level1),
            "IOException deep in cause chain should be retryable");
    }

    // ═══════════════════════════════════════════════════════════
    // 非重试异常
    // ═══════════════════════════════════════════════════════════

    @Test
    void notRetryableOnNullPointerException() {
        assertFalse(model.isRetryable(new NullPointerException("null")),
            "NullPointerException should NOT be retryable");
    }

    @Test
    void notRetryableOnIllegalArgumentException() {
        assertFalse(model.isRetryable(new IllegalArgumentException("bad arg")),
            "IllegalArgumentException should NOT be retryable");
    }

    @Test
    void notRetryableOnRuntimeException() {
        assertFalse(model.isRetryable(new RuntimeException("generic")),
            "RuntimeException should NOT be retryable");
    }

    // ═══════════════════════════════════════════════════════════
    // null / 边界
    // ═══════════════════════════════════════════════════════════

    @Test
    void notRetryableOnNull() {
        assertFalse(model.isRetryable(new RuntimeException((Throwable) null)),
            "Exception with null cause should NOT retry");
    }

    // ═══════════════════════════════════════════════════════════
    // 其他异常类型
    // ═══════════════════════════════════════════════════════════

    @Test
    void retryableOnConnectException() {
        assertTrue(model.isRetryable(new java.net.ConnectException("connection refused")),
            "ConnectException (subclass of IOException) should be retryable");
    }

    // ═══════════════════════════════════════════════════════════
    // Test helper — 暴露 protected isRetryable
    // ═══════════════════════════════════════════════════════════

    static class TestChatModel extends ChatModelBase {
        TestChatModel() { super("test", "test-model", new OpenAiMessageFormatter()); }

        @Override protected Map<String, String> buildAuthHeaders() { return Map.of(); }
        @Override protected String buildApiUrl() { return "http://localhost/test"; }
        @Override public int getMaxInputTokens() { return 4096; }

        @Override
        public boolean isRetryable(Throwable e) { return super.isRetryable(e); }
    }
}
