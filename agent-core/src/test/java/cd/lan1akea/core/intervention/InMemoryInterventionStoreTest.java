package cd.lan1akea.core.intervention;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

class InMemoryInterventionStoreTest {

    private InMemoryInterventionStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryInterventionStore();
    }

    @Test
    void createAndGetById_shouldReturnRequest() {
        InterventionRequest req = buildRequest("s1", InterventionRequest.Type.TOOL_APPROVAL);
        String id = store.create(req);
        InterventionRequest found = store.getById(id);
        assertNotNull(found);
        assertEquals(id, found.getInterventionId());
        assertEquals(InterventionRequest.Status.PENDING, found.getStatus());
    }

    @Test
    void approve_shouldChangeStatus() {
        InterventionRequest req = buildRequest("s1", InterventionRequest.Type.TOOL_APPROVAL);
        String id = store.create(req);
        store.approve(id, "user1", "ok");
        assertEquals(InterventionRequest.Status.APPROVED, store.getById(id).getStatus());
    }

    @Test
    void deny_shouldChangeStatus() {
        InterventionRequest req = buildRequest("s1", InterventionRequest.Type.TOOL_APPROVAL);
        String id = store.create(req);
        store.deny(id, "user1", "no");
        assertEquals(InterventionRequest.Status.DENIED, store.getById(id).getStatus());
    }

    @Test
    void clarify_shouldStoreModifiedArgs() {
        InterventionRequest req = buildRequest("s1", InterventionRequest.Type.TOOL_CLARIFY);
        String id = store.create(req);
        store.clarify(id, "user1", "fix", Map.of("amount", 50));
        assertEquals(InterventionRequest.Status.CLARIFIED, store.getById(id).getStatus());
        assertEquals(Map.of("amount", 50), store.getById(id).getModifiedArgs());
    }

    @Test
    void getAllPending_shouldOnlyReturnPending() {
        String id1 = store.create(buildRequest("s1", InterventionRequest.Type.TOOL_APPROVAL));
        String id2 = store.create(buildRequest("s2", InterventionRequest.Type.BUSINESS_PAUSE));
        store.approve(id2, "u", "ok");
        assertEquals(1, store.getAllPending().size());
        assertEquals(id1, store.getAllPending().get(0).getInterventionId());
    }

    @Test
    void getPendingBySession_shouldFilterBySession() {
        store.create(buildRequest("s1", InterventionRequest.Type.TOOL_APPROVAL));
        store.create(buildRequest("s2", InterventionRequest.Type.TOOL_APPROVAL));
        assertEquals(1, store.getPendingBySession("s1").size());
        assertEquals(0, store.getPendingBySession("s3").size());
    }

    @Test
    void cleanupExpired_shouldMarkExpired() {
        InterventionRequest req = InterventionRequest.builder()
                .sessionId("s1").type(InterventionRequest.Type.BUSINESS_PAUSE)
                .ttlMinutes(0).build(); // expired immediately
        store.create(req);
        store.cleanupExpired();
        assertTrue(store.getAllPending().isEmpty());
        assertEquals(InterventionRequest.Status.EXPIRED, store.getById(req.getInterventionId()).getStatus());
    }

    @Test
    void getAll_shouldReturnAll() {
        store.create(buildRequest("s1", InterventionRequest.Type.TOOL_APPROVAL));
        store.create(buildRequest("s2", InterventionRequest.Type.BUSINESS_PAUSE));
        assertEquals(2, store.getAll().size());
    }

    @Test
    void approveOnNonExistent_shouldNotThrow() {
        assertDoesNotThrow(() -> store.approve("nonexistent", "u", "ok"));
    }

    private InterventionRequest buildRequest(String sessionId, InterventionRequest.Type type) {
        return InterventionRequest.builder()
                .sessionId(sessionId).type(type)
                .toolName("test").question("?").ttlMinutes(60).build();
    }
}
