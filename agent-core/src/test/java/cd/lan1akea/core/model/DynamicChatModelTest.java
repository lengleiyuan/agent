package cd.lan1akea.core.model;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.MsgRole;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DynamicChatModelTest {

    static class FakeModel implements ChatModel {
        private final String name;
        FakeModel(String name) { this.name = name; }
        @Override public String getProvider() { return name; }
        @Override public String getModelName() { return name; }
        @Override public Mono<ChatResponse> chat(List<Msg> msgs, GenerateOptions opts) {
            Msg m = Msg.builder(MsgRole.ASSISTANT).addText(name).build();
            return Mono.just(new ChatResponse(m, new ChatUsage(0, 0), "stop", name));
        }
        @Override public Flux<ChatStreamChunk> stream(List<Msg> msgs, GenerateOptions opts) {
            return Flux.just(ChatStreamChunk.builder().delta(name).type("text").build());
        }
        @Override public Mono<ChatResponse> chatWithTools(List<Msg> msgs, List<ToolSchema> tools, GenerateOptions opts) {
            return chat(msgs, opts);
        }
        @Override public Flux<ChatStreamChunk> streamWithTools(List<Msg> msgs, List<ToolSchema> tools, GenerateOptions opts) {
            return stream(msgs, opts);
        }
    }

    @Test
    void testSwapChangesDelegate() {
        DynamicChatModel d = new DynamicChatModel(new FakeModel("A"));
        assertEquals("A", d.getProvider());
        assertEquals("A", d.getModelName());

        d.swap(new FakeModel("B"));
        assertEquals("B", d.getProvider());
        assertEquals("B", d.getModelName());
    }

    @Test
    void testChatDelegatesToCurrent() {
        DynamicChatModel d = new DynamicChatModel(new FakeModel("A"));
        Msg msg = Msg.builder(MsgRole.USER).addText("hi").build();
        ChatResponse r = d.chat(List.of(msg), GenerateOptions.defaults()).block(Duration.ofSeconds(2));
        assertNotNull(r);
        assertEquals("A", r.getMessage().getTextContent());

        d.swap(new FakeModel("B"));
        r = d.chat(List.of(msg), GenerateOptions.defaults()).block(Duration.ofSeconds(2));
        assertEquals("B", r.getMessage().getTextContent());
    }

    @Test
    void testStreamDelegatesToCurrent() {
        DynamicChatModel d = new DynamicChatModel(new FakeModel("A"));
        Msg msg = Msg.builder(MsgRole.USER).addText("hi").build();
        StepVerifier.create(d.stream(List.of(msg), GenerateOptions.defaults()))
            .assertNext(c -> assertEquals("A", c.getDelta()))
            .verifyComplete();

        d.swap(new FakeModel("B"));
        StepVerifier.create(d.stream(List.of(msg), GenerateOptions.defaults()))
            .assertNext(c -> assertEquals("B", c.getDelta()))
            .verifyComplete();
    }

    @Test
    void testGetDelegate() {
        FakeModel f = new FakeModel("X");
        DynamicChatModel d = new DynamicChatModel(f);
        assertSame(f, d.getDelegate());
    }

    @Test
    void testDefaultsDelegated() {
        FakeModel f = new FakeModel("X");
        DynamicChatModel d = new DynamicChatModel(f);
        assertEquals(f.getMaxInputTokens(), d.getMaxInputTokens());
        assertEquals(f.getDefaultMaxTokens(), d.getDefaultMaxTokens());
        assertEquals(f.getDefaultTemperature(), d.getDefaultTemperature(), 0.001);
    }
}
