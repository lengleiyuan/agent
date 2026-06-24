package cd.lan1akea.core.state;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.MsgRole;
import cd.lan1akea.core.message.UserMessage;
import cd.lan1akea.core.session.ChatTurn;
import cd.lan1akea.core.session.Session;
import cd.lan1akea.core.session.SessionId;
import cd.lan1akea.core.session.SessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentStateStore 单元测试。
 * 验证会话 CRUD、对话轮次持久化、检查点存储和恢复。
 */
class AgentStateStoreTest {

    private InMemoryAgentStateStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryAgentStateStore();
    }

    // ========================================================================
    // 会话生命周期
    // ========================================================================

    @Test
    void testCreateAndFindSession() {
        Session session = new Session(new SessionId("s1"), 1, "test-agent",
            SessionState.ACTIVE, null, null, null);

        store.create(session).block();
        Session found = store.findById(new SessionId("s1")).block();

        assertNotNull(found);
        assertEquals("s1", found.getId().getValue());
        assertEquals("test-agent", found.getAgentName());
    }

    @Test
    void testFindByIdNotFound() {
        Session found = store.findById(new SessionId("nonexistent")).block();
        assertNull(found);
    }

    @Test
    void testListByTenant() {
        store.create(new Session(new SessionId("s1"), 1, "a", SessionState.ACTIVE, null, null, null)).block();
        store.create(new Session(new SessionId("s2"), 1, "b", SessionState.ACTIVE, null, null, null)).block();
        store.create(new Session(new SessionId("s3"), 2, "c", SessionState.ACTIVE, null, null, null)).block();

        List<Session> tenant1 = store.listByTenant(1).collectList().block();
        assertEquals(2, tenant1.size());

        List<Session> tenant2 = store.listByTenant(2).collectList().block();
        assertEquals(1, tenant2.size());
    }

    @Test
    void testUpdateAndCloseSession() {
        Session session = new Session(new SessionId("s1"), 1, "a", SessionState.ACTIVE, null, null, null);
        store.create(session).block();

        store.close(new SessionId("s1")).block();
        Session found = store.findById(new SessionId("s1")).block();
        assertEquals(SessionState.CLOSED, found.getState());
    }

    @Test
    void testDeleteSession() {
        store.create(new Session(new SessionId("s1"), 1, "a", SessionState.ACTIVE, null, null, null)).block();
        store.delete(new SessionId("s1")).block();
        assertNull(store.findById(new SessionId("s1")).block());
    }

    // ========================================================================
    // 对话持久化
    // ========================================================================

    @Test
    void testAddTurnAndGetHistory() {
        Session session = new Session(new SessionId("s1"), 1, "a", SessionState.ACTIVE, null, null, null);
        store.create(session).block();

        ChatTurn turn = new ChatTurn(1, 1, 0, "hello", "hi there", null, LocalDateTime.now());
        store.addTurn(new SessionId("s1"), turn).block();

        List<Msg> history = store.getHistory(new SessionId("s1")).collectList().block();
        assertNotNull(history);
        assertFalse(history.isEmpty());
        assertTrue(history.stream().anyMatch(m -> m.getTextContent().contains("hello")));
    }

    @Test
    void testGetHistoryEmptySession() {
        store.create(new Session(new SessionId("s1"), 1, "a", SessionState.ACTIVE, null, null, null)).block();
        List<Msg> history = store.getHistory(new SessionId("s1")).collectList().block();
        assertTrue(history.isEmpty());
    }

    // ========================================================================
    // 检查点
    // ========================================================================

    @Test
    void testSaveAndLoadCheckpoint() {
        AgentState state = new AgentState("agent1", "s1", 3,
            List.of(UserMessage.of("test")), Map.of("tool", "active"),
            500, false, null, System.currentTimeMillis());

        store.saveCheckpoint(state).block();

        AgentState loaded = store.loadLatestCheckpoint("s1").block();
        assertNotNull(loaded);
        assertEquals("agent1", loaded.getAgentName());
        assertEquals("s1", loaded.getSessionId());
        assertEquals(3, loaded.getIteration());
        assertEquals(500, loaded.getTotalTokens());
        assertFalse(loaded.isShutdownInterrupted());
        assertNotNull(loaded.getMessages());
        assertEquals(1, loaded.getMessages().size());
    }

    @Test
    void testLoadCheckpointNotFound() {
        AgentState loaded = store.loadLatestCheckpoint("nonexistent").block();
        assertNull(loaded);
    }

    @Test
    void testDeleteCheckpoints() {
        store.saveCheckpoint(new AgentState("a", "s1", 1, null, null, 0, false, null,
            System.currentTimeMillis())).block();
        store.deleteCheckpoints("s1").block();

        AgentState loaded = store.loadLatestCheckpoint("s1").block();
        assertNull(loaded);
    }

    @Test
    void testShutdownRecovery() {
        AgentState interrupted = new AgentState("agent1", "s1", 2,
            List.of(UserMessage.of("in progress")), Map.of(),
            200, true, null, System.currentTimeMillis());

        store.saveCheckpoint(interrupted).block();

        AgentState loaded = store.loadLatestCheckpoint("s1").block();
        assertNotNull(loaded);
        assertTrue(loaded.isShutdownInterrupted());

        // 清除中断标志
        loaded.setShutdownInterrupted(false);
        store.saveCheckpoint(loaded).block();

        AgentState cleared = store.loadLatestCheckpoint("s1").block();
        assertFalse(cleared.isShutdownInterrupted());
    }
}
