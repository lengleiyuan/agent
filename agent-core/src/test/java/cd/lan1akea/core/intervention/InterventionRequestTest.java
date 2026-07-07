package cd.lan1akea.core.intervention;

import cd.lan1akea.core.message.UserMessage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

class InterventionRequestTest {

    @Test
    void build_shouldSetAllFields() {
        InterventionRequest req = InterventionRequest.builder()
                .sessionId("s1").requestId("r1").type(InterventionRequest.Type.TOOL_APPROVAL)
                .toolName("transfer").question("确认?")
                .toolArgs(Map.of("amount", 100))
                .ttlMinutes(5).build();
        assertEquals("s1", req.getSessionId());
        assertEquals(InterventionRequest.Type.TOOL_APPROVAL, req.getType());
        assertEquals(InterventionRequest.Status.PENDING, req.getStatus());
        assertNotNull(req.getInterventionId());
        assertNotNull(req.getCreatedAt());
        assertNotNull(req.getExpiresAt());
    }

    @Test
    void approve_shouldTransitionStatus() {
        InterventionRequest req = buildPending();
        req.approve("user1", "同意");
        assertEquals(InterventionRequest.Status.APPROVED, req.getStatus());
        assertEquals("user1", req.getResolverId());
        assertEquals("同意", req.getResolution());
        assertNotNull(req.getResolvedAt());
    }

    @Test
    void deny_shouldTransitionStatus() {
        InterventionRequest req = buildPending();
        req.deny("user1", "拒绝");
        assertEquals(InterventionRequest.Status.DENIED, req.getStatus());
    }

    @Test
    void clarify_shouldStoreModifiedArgs() {
        InterventionRequest req = buildPending();
        Map<String, Object> modified = Map.of("amount", 50);
        req.clarify("user1", "金额减半", modified);
        assertEquals(InterventionRequest.Status.CLARIFIED, req.getStatus());
        assertEquals(modified, req.getModifiedArgs());
    }

    @Test
    void expire_shouldTransitionToExpired() {
        InterventionRequest req = buildPending();
        req.expire();
        assertEquals(InterventionRequest.Status.EXPIRED, req.getStatus());
    }

    @Test
    void isExpired_zeroTtl_shouldBeExpired() {
        InterventionRequest req = InterventionRequest.builder()
                .sessionId("s1").type(InterventionRequest.Type.TOOL_CLARIFY).ttlMinutes(0).build();
        assertTrue(req.isExpired());
    }

    @Test
    void isExpired_positiveTtl_shouldNotBeExpired() {
        InterventionRequest req = InterventionRequest.builder()
                .sessionId("s1").type(InterventionRequest.Type.TOOL_CLARIFY).ttlMinutes(60).build();
        assertFalse(req.isExpired());
    }

    @Test
    void recentMessages_shouldBeStored() {
        UserMessage msg = UserMessage.of("hello");
        InterventionRequest req = InterventionRequest.builder()
                .sessionId("s1").type(InterventionRequest.Type.TOOL_CLARIFY)
                .recentMessages(List.of(msg)).build();
        assertEquals(1, req.getRecentMessages().size());
    }

    private InterventionRequest buildPending() {
        return InterventionRequest.builder()
                .sessionId("s1").type(InterventionRequest.Type.TOOL_APPROVAL)
                .toolName("t").question("?").build();
    }
}
