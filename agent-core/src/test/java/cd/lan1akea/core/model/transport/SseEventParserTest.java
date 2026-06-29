package cd.lan1akea.core.model.transport;

import cd.lan1akea.core.model.ChatStreamChunk;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;

class SseEventParserTest {

    private final SseEventParser parser = new SseEventParser();

    @Test
    void testSingleTextChunk() {
        StepVerifier.create(parser.parse(Flux.just(ss("{\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}"))))
            .assertNext(c -> {
                assertEquals("hello", c.getDelta());
                assertEquals(ChatStreamChunk.TYPE_TEXT, c.getType());
            }).verifyComplete();
    }

    @Test
    void testThinkingChunk() {
        StepVerifier.create(parser.parse(Flux.just(ss("{\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking...\"}}]}"))))
            .assertNext(c -> {
                assertEquals("thinking...", c.getDelta());
                assertEquals(ChatStreamChunk.TYPE_THINKING, c.getType());
            }).verifyComplete();
    }

    @Test
    void testToolCallStart() {
        StepVerifier.create(parser.parse(Flux.just(ss(
            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"calc\",\"arguments\":\"\"}}]}}]}"))))
            .assertNext(c -> {
                assertEquals(ChatStreamChunk.TYPE_TOOL_USE_START, c.getType());
                assertEquals("c1", c.getToolUseId());
                assertEquals("calc", c.getToolName());
            }).verifyComplete();
    }

    @Test
    void testToolCallDelta() {
        StepVerifier.create(parser.parse(Flux.just(ss(
            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"arguments\":\"{\\\"x\\\":1}\"}}]}}]}"))))
            .assertNext(c -> {
                assertEquals(ChatStreamChunk.TYPE_TOOL_USE_DELTA, c.getType());
                assertEquals("c1", c.getToolUseId());
                assertEquals("{\"x\":1}", c.getDelta());
            }).verifyComplete();
    }

    @Test
    void testToolCallWithNameAndArgsTogether() {
        // OpenAI 风格：name 和 args 同帧 → 产出 START + DELTA
        StepVerifier.create(parser.parse(Flux.just(ss(
            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"calc\",\"arguments\":\"{\\\"expr\\\":\\\"1+1\\\"}\"}}]}}]}"))))
            .assertNext(c -> {
                assertEquals(ChatStreamChunk.TYPE_TOOL_USE_START, c.getType());
                assertEquals("c1", c.getToolUseId());
            })
            .assertNext(c -> {
                assertEquals(ChatStreamChunk.TYPE_TOOL_USE_DELTA, c.getType());
                assertEquals("c1", c.getToolUseId());
                assertEquals("{\"expr\":\"1+1\"}", c.getDelta());
            }).verifyComplete();
    }

    @Test
    void testDeltaWithoutIdUsesIndexMapping() {
        // DeepSeek 风格：START 有 id，DELTA 无 id → 通过 index 映射
        StepVerifier.create(parser.parse(Flux.just(
            ss("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"calc\",\"arguments\":\"\"}}]}}]}"),
            ss("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"expr\\\":\\\"1+1\\\"}\"}}]}}]}"),
            ss("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\"}}]}}]}")
        )))
            .assertNext(c -> assertEquals("c1", c.getToolUseId()))  // START
            .assertNext(c -> {
                assertEquals("c1", c.getToolUseId());  // DELTA (no id → index fallback)
                assertEquals("{\"expr\":\"1+1\"}", c.getDelta());
            })
            .verifyComplete();  // empty args → filtered out
    }

    @Test
    void testMultipleParallelToolCalls() {
        // 并行调用 3 个工具
        StepVerifier.create(parser.parse(Flux.just(ss(
            "{\"choices\":[{\"delta\":{\"tool_calls\":["
            + "{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"calc\",\"arguments\":\"\"}},"
            + "{\"index\":1,\"id\":\"c2\",\"function\":{\"name\":\"calc\",\"arguments\":\"\"}},"
            + "{\"index\":2,\"id\":\"c3\",\"function\":{\"name\":\"calc\",\"arguments\":\"\"}}"
            + "]}}]}"))))
            .assertNext(c -> { assertEquals("c1", c.getToolUseId()); assertEquals("calc", c.getToolName()); })
            .assertNext(c -> { assertEquals("c2", c.getToolUseId()); assertEquals("calc", c.getToolName()); })
            .assertNext(c -> { assertEquals("c3", c.getToolUseId()); assertEquals("calc", c.getToolName()); })
            .verifyComplete();
    }

    @Test
    void testMultipleToolsWithInterleavedDeltas() {
        // idx 0 start → idx 1 start → idx 0 delta → idx 1 delta
        StepVerifier.create(parser.parse(Flux.just(
            ss("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"calc\",\"arguments\":\"\"}}]}}]}"),
            ss("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"c2\",\"function\":{\"name\":\"calc\",\"arguments\":\"\"}}]}}]}"),
            ss("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"a\\\":1}\"}}]}}]}"),
            ss("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"function\":{\"arguments\":\"{\\\"b\\\":2}\"}}]}}]}")
        )))
            .assertNext(c -> { assertEquals("c1", c.getToolUseId()); assertEquals("calc", c.getToolName()); })
            .assertNext(c -> { assertEquals("c2", c.getToolUseId()); assertEquals("calc", c.getToolName()); })
            .assertNext(c -> { assertEquals("c1", c.getToolUseId()); assertEquals("{\"a\":1}", c.getDelta()); })
            .assertNext(c -> { assertEquals("c2", c.getToolUseId()); assertEquals("{\"b\":2}", c.getDelta()); })
            .verifyComplete();
    }

    @Test
    void testMixedContentAndTools() {
        StepVerifier.create(parser.parse(Flux.just(ss(
            "{\"choices\":[{\"delta\":{\"content\":\"ok\",\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"calc\",\"arguments\":\"\"}}]}}]}"))))
            .assertNext(c -> assertEquals(ChatStreamChunk.TYPE_TEXT, c.getType()))
            .assertNext(c -> assertEquals(ChatStreamChunk.TYPE_TOOL_USE_START, c.getType()))
            .verifyComplete();
    }

    @Test
    void testFinishReason() {
        StepVerifier.create(parser.parse(Flux.just(ss("{\"choices\":[{\"finish_reason\":\"stop\"}]}"))))
            .assertNext(c -> assertEquals("stop", c.getFinishReason()))
            .verifyComplete();
    }

    @Test
    void testDoneFiltered() {
        StepVerifier.create(parser.parse(Flux.just("data:[DONE]"))).verifyComplete();
    }

    @Test
    void testInvalidJsonReturnsEmpty() {
        StepVerifier.create(parser.parse(Flux.just("data:not json"))).verifyComplete();
    }

    private static String ss(String json) { return "data:" + json; }
}
