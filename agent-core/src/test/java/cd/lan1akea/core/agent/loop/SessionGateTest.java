package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.model.ChatStreamChunk;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionGateTest {

    private final SessionGate gate = new SessionGate();

    @Test
    void nullSession_shouldExecuteImmediately() {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        Mono<String> work = Mono.fromCallable(() -> { order.add("a"); return "a"; });

        StepVerifier.create(gate.enqueue(null, work))
                .expectNext("a")
                .verifyComplete();
        assertEquals(List.of("a"), order);
    }

    @Test
    void sameSession_shouldSerializeExecution() {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        String sid = "session-1";

        Mono<String> work1 = Mono.just("a").delayElement(Duration.ofMillis(100))
                .doOnSubscribe(s -> order.add("start-1"))
                .doOnNext(v -> order.add(v));
        Mono<String> work2 = Mono.just("b")
                .doOnSubscribe(s -> order.add("start-2"))
                .doOnNext(v -> order.add(v));

        Mono<String> gated1 = gate.enqueue(sid, work1);
        Mono<String> gated2 = gate.enqueue(sid, work2);

        StepVerifier.create(Mono.zip(gated1, gated2))
                .expectNextCount(1)
                .verifyComplete();

        // work2 should start after work1 completes
        int idx1 = order.indexOf("a");
        int idx2 = order.indexOf("start-2");
        assertTrue(idx2 > idx1, "work2 should start after work1 completes: " + order);
    }

    @Test
    void differentSessions_shouldRunConcurrently() {
        List<String> order = Collections.synchronizedList(new ArrayList<>());

        Mono<String> work1 = Mono.just("a").delayElement(Duration.ofMillis(200))
                .doOnSubscribe(s -> order.add("start-1"));
        Mono<String> work2 = Mono.just("b")
                .doOnSubscribe(s -> order.add("start-2"));

        Mono<String> gated1 = gate.enqueue("session-1", work1);
        Mono<String> gated2 = gate.enqueue("session-2", work2);

        StepVerifier.create(Mono.zip(gated1, gated2))
                .expectNextCount(1)
                .verifyComplete();

        int idx1 = order.indexOf("start-1");
        int idx2 = order.indexOf("start-2");
        assertTrue(Math.abs(idx1 - idx2) <= 1, "different sessions should not block each other: " + order);
    }

    @Test
    void enqueueStream_sameSession_shouldSerialize() {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        String sid = "stream-session";

        Flux<ChatStreamChunk> work1 = Flux.just(chunk("a")).delayElements(Duration.ofMillis(100))
                .doOnSubscribe(s -> order.add("start-1"))
                .doOnComplete(() -> order.add("end-1"));
        Flux<ChatStreamChunk> work2 = Flux.just(chunk("b"))
                .doOnSubscribe(s -> order.add("start-2"))
                .doOnComplete(() -> order.add("end-2"));

        Flux<ChatStreamChunk> gated1 = gate.enqueueStream(sid, work1);
        Flux<ChatStreamChunk> gated2 = gate.enqueueStream(sid, work2);

        StepVerifier.create(
                gated1.collectList().then(Mono.defer(() -> gated2.collectList())))
                .expectNextCount(1)
                .verifyComplete();

        int end1Idx = order.indexOf("end-1");
        int start2Idx = order.indexOf("start-2");
        assertTrue(start2Idx > end1Idx, "stream work2 should start after work1 completes: " + order);
    }

    @Test
    void enqueue_errorInPrevious_shouldNotBlockNext() {
        String sid = "error-session";
        List<String> order = Collections.synchronizedList(new ArrayList<>());

        Mono<String> failing = Mono.<String>error(new RuntimeException("boom"))
                .doOnSubscribe(s -> order.add("fail-start"))
                .doFinally(s -> order.add("fail-end"));
        Mono<String> next = Mono.just("ok")
                .doOnSubscribe(s -> order.add("next-start"))
                .doOnNext(v -> order.add(v));

        gate.enqueue(sid, failing).onErrorResume(e -> Mono.empty()).subscribe();
        // Sleep briefly to let first work complete (error)
        try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        StepVerifier.create(gate.enqueue(sid, next))
                .expectNext("ok")
                .verifyComplete();

        assertTrue(order.contains("next-start"), "next should start after error: " + order);
    }

    private static ChatStreamChunk chunk(String text) {
        return ChatStreamChunk.builder().delta(text).build();
    }
}
