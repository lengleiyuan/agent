package cd.lan1akea.core.session;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.UserMessage;
import cd.lan1akea.core.message.AssistantMessage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Session 并发安全测试。
 * 验证 P0 改动：turns 改用 synchronizedList 防止并发 addTurn 丢数据。
 */
class SessionThreadSafetyTest {

    @Test
    void concurrentAddTurnDoesNotLoseData() throws Exception {
        Session session = new Session(new SessionId("s1"), "t1", "agent",
            SessionState.ACTIVE, Collections.emptyList(), null, null);

        int threads = 20;
        int turnsPerThread = 50;
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger turnIdCounter = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                for (int i = 0; i < turnsPerThread; i++) {
                    int turnId = turnIdCounter.getAndIncrement();
                    List<Msg> userMsgs = List.of(UserMessage.of("msg-" + turnId));
                    List<Msg> asstMsgs = List.of(AssistantMessage.of("resp-" + turnId));
                    ChatTurn turn = new ChatTurn(turnId, "s1", i,
                        null, null, null, LocalDateTime.now(),
                        userMsgs, asstMsgs, null);
                    session.addTurn(turn);
                }
                latch.countDown();
            }).start();
        }

        latch.await();

        List<ChatTurn> turns = session.getTurns();
        assertEquals(threads * turnsPerThread, turns.size(),
            "All turns should be preserved under concurrent writes");
    }

    @Test
    void concurrentReadAndWriteDoesNotThrow() throws Exception {
        Session session = new Session(new SessionId("s2"), "t1", "agent",
            SessionState.ACTIVE, Collections.emptyList(), null, null);

        int writers = 10;
        int readers = 10;
        int opsPerThread = 50;
        CountDownLatch latch = new CountDownLatch(writers + readers);
        CopyOnWriteArrayList<String> errors = new CopyOnWriteArrayList<>();

        for (int w = 0; w < writers; w++) {
            final int writerId = w;
            new Thread(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        ChatTurn turn = new ChatTurn(writerId * 1000 + i, "s2", i,
                            null, null, null, LocalDateTime.now(),
                            List.of(UserMessage.of("w" + writerId)),
                            List.of(AssistantMessage.of("r" + writerId)), null);
                        session.addTurn(turn);
                    }
                } catch (Exception e) {
                    errors.add("writer-" + writerId + ": " + e.getMessage());
                }
                latch.countDown();
            }).start();
        }

        for (int r = 0; r < readers; r++) {
            final int readerId = r;
            new Thread(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        List<ChatTurn> turns = session.getTurns();
                        assertNotNull(turns);
                    }
                } catch (Exception e) {
                    errors.add("reader-" + readerId + ": " + e.getMessage());
                }
                latch.countDown();
            }).start();
        }

        latch.await();

        assertTrue(errors.isEmpty(), "No errors expected, got: " + errors);
        assertEquals(writers * opsPerThread, session.getTurns().size());
    }

    @Test
    void stateTransitionsAreThreadSafe() throws Exception {
        Session session = new Session(new SessionId("s3"), "t1", "agent",
            SessionState.ACTIVE, Collections.emptyList(), null, null);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger pauseCount = new AtomicInteger(0);
        AtomicInteger resumeCount = new AtomicInteger(0);

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Thread t = new Thread(() -> {
                try { latch.await(); } catch (InterruptedException ignored) {}
                session.pause();
                pauseCount.incrementAndGet();
                session.resume();
                resumeCount.incrementAndGet();
            });
            threads.add(t);
            t.start();
        }

        latch.countDown();
        for (Thread t : threads) t.join(2000);

        assertTrue(pauseCount.get() >= 10);
        assertTrue(resumeCount.get() >= 10);
        // 最后状态可能是 ACTIVE 或 PAUSED，取决于执行顺序
        assertNotNull(session.getState());
    }

    @Test
    void singleThreadAddTurnWorksCorrectly() {
        Session session = new Session(new SessionId("s4"), "t1", "agent",
            SessionState.ACTIVE, null, null, null);

        assertEquals(0, session.getTurnCount());

        ChatTurn t1 = new ChatTurn(1, "s4", 0, null, null, null, LocalDateTime.now(),
            List.of(UserMessage.of("hello")), List.of(AssistantMessage.of("hi")), null);
        session.addTurn(t1);
        assertEquals(1, session.getTurnCount());

        ChatTurn t2 = new ChatTurn(2, "s4", 1, null, null, null, LocalDateTime.now(),
            List.of(UserMessage.of("bye")), List.of(AssistantMessage.of("bye")), null);
        session.addTurn(t2);
        assertEquals(2, session.getTurnCount());

        List<ChatTurn> turns = session.getTurns();
        assertEquals("hello", turns.get(0).getUserMessages().get(0).getTextContent());
        assertEquals("bye", turns.get(1).getUserMessages().get(0).getTextContent());
    }

    @Test
    void sessionCloseAndPause() {
        Session session = new Session(new SessionId("s5"), "t1", "agent",
            SessionState.ACTIVE, null, null, null);

        assertEquals(SessionState.ACTIVE, session.getState());
        session.pause();
        assertEquals(SessionState.PAUSED, session.getState());
        session.resume();
        assertEquals(SessionState.ACTIVE, session.getState());
        session.close();
        assertEquals(SessionState.CLOSED, session.getState());
    }
}
