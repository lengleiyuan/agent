package cd.lan1akea.core.approval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InMemoryApprovalStore 全面测试。
 * 覆盖 P0 改动：cleanupExpired + 完整 CRUD + 审批流程 + 并发安全。
 */
class InMemoryApprovalStoreTest {

    private InMemoryApprovalStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryApprovalStore();
    }

    // ═══════════════════════════════════════════════════════════
    // createPending + getById
    // ═══════════════════════════════════════════════════════════

    @Test
    void createPendingReturnsApprovalId() {
        PendingApproval pa = PendingApproval.builder()
            .sessionId("s1").toolName("transfer").question("确认转账?").build();
        String id = store.createPending(pa);
        assertNotNull(id);
        assertFalse(id.isBlank());
    }

    @Test
    void getByIdReturnsCreatedApproval() {
        PendingApproval pa = PendingApproval.builder()
            .sessionId("s1").toolName("transfer").question("确认?").build();
        String id = store.createPending(pa);
        PendingApproval found = store.getById(id);
        assertNotNull(found);
        assertEquals("transfer", found.getToolName());
        assertEquals(PendingApproval.Status.PENDING, found.getStatus());
    }

    @Test
    void getByIdReturnsNullForUnknownId() {
        assertNull(store.getById("nonexistent"));
    }

    // ═══════════════════════════════════════════════════════════
    // approve / deny
    // ═══════════════════════════════════════════════════════════

    @Test
    void approveChangesStatus() {
        PendingApproval pa = PendingApproval.builder()
            .sessionId("s1").toolName("echo").question("ok?").build();
        String id = store.createPending(pa);
        store.approve(id, "admin", "允许执行");

        PendingApproval found = store.getById(id);
        assertEquals(PendingApproval.Status.APPROVED, found.getStatus());
        assertEquals("admin", found.getApproverId());
        assertEquals("允许执行", found.getApproverComment());
    }

    @Test
    void denyChangesStatus() {
        PendingApproval pa = PendingApproval.builder()
            .sessionId("s1").toolName("rm").question("删除?").build();
        String id = store.createPending(pa);
        store.deny(id, "admin", "不允许");

        PendingApproval found = store.getById(id);
        assertEquals(PendingApproval.Status.DENIED, found.getStatus());
        assertEquals("admin", found.getApproverId());
    }

    @Test
    void approveOnNonexistentIdDoesNotThrow() {
        assertDoesNotThrow(() -> store.approve("nonexistent", "admin", "ok"));
    }

    @Test
    void denyOnNonexistentIdDoesNotThrow() {
        assertDoesNotThrow(() -> store.deny("nonexistent", "admin", "no"));
    }

    // ═══════════════════════════════════════════════════════════
    // isApproved / consume
    // ═══════════════════════════════════════════════════════════

    @Test
    void isApprovedReturnsTrueAfterApprove() {
        PendingApproval pa = PendingApproval.builder()
            .sessionId("s1").toolName("transfer").question("?").build();
        String id = store.createPending(pa);
        store.approve(id, "admin", "ok");

        assertTrue(store.isApproved("s1", "transfer"));
    }

    @Test
    void isApprovedReturnsFalseBeforeApprove() {
        PendingApproval pa = PendingApproval.builder()
            .sessionId("s1").toolName("transfer").question("?").build();
        store.createPending(pa);

        assertFalse(store.isApproved("s1", "transfer"));
    }

    @Test
    void isApprovedReturnsFalseAfterDeny() {
        PendingApproval pa = PendingApproval.builder()
            .sessionId("s1").toolName("transfer").question("?").build();
        String id = store.createPending(pa);
        store.deny(id, "admin", "no");

        assertFalse(store.isApproved("s1", "transfer"));
    }

    @Test
    void consumeRemovesApprovedKey() {
        PendingApproval pa = PendingApproval.builder()
            .sessionId("s1").toolName("transfer").question("?").build();
        String id = store.createPending(pa);
        store.approve(id, "admin", "ok");
        assertTrue(store.isApproved("s1", "transfer"));

        store.consume("s1", "transfer");
        assertFalse(store.isApproved("s1", "transfer"), "consume() should remove approved key");
    }

    @Test
    void consumeOnNonexistentDoesNotThrow() {
        assertDoesNotThrow(() -> store.consume("nosession", "notool"));
    }

    // ═══════════════════════════════════════════════════════════
    // cleanupExpired
    // ═══════════════════════════════════════════════════════════

    @Test
    void cleanupExpiredRemovesExpiredApprovals() {
        // 创建一个已过期的审批（过期时间在过去）
        PendingApproval expired = PendingApproval.builder()
            .sessionId("s1").toolName("old_tool").question("?")
            .expiresAt(System.currentTimeMillis() - 1000).build();
        store.createPending(expired);

        // 创建一个未过期的审批
        PendingApproval active = PendingApproval.builder()
            .sessionId("s2").toolName("new_tool").question("?")
            .expiresAt(System.currentTimeMillis() + 300_000).build();
        String activeId = store.createPending(active);

        store.cleanupExpired();

        PendingApproval foundActive = store.getById(activeId);
        assertNotNull(foundActive, "Active approval should not be removed");
        assertEquals(PendingApproval.Status.PENDING, foundActive.getStatus());
    }

    @Test
    void cleanupExpiredExpiresPendingApprovals() {
        PendingApproval pending = PendingApproval.builder()
            .sessionId("s1").toolName("exp_tool").question("?")
            .expiresAt(System.currentTimeMillis() - 500).build();
        String id = store.createPending(pending);

        store.cleanupExpired();

        PendingApproval found = store.getById(id);
        assertNotNull(found);
        assertEquals(PendingApproval.Status.EXPIRED, found.getStatus());
    }

    @Test
    void cleanupExpiredDoesNotAffectApproved() {
        PendingApproval approved = PendingApproval.builder()
            .sessionId("s1").toolName("app_tool").question("?")
            .expiresAt(System.currentTimeMillis() - 1000).build();
        String id = store.createPending(approved);
        store.approve(id, "admin", "ok");

        store.cleanupExpired();

        PendingApproval found = store.getById(id);
        assertNotNull(found);
        assertEquals(PendingApproval.Status.APPROVED, found.getStatus(),
            "Approved items should not be expired by cleanup");
    }

    // ═══════════════════════════════════════════════════════════
    // getPendingBySession
    // ═══════════════════════════════════════════════════════════

    @Test
    void getPendingBySessionFiltersCorrectly() {
        PendingApproval pa1 = PendingApproval.builder().sessionId("s1").toolName("t1").question("?").build();
        PendingApproval pa2 = PendingApproval.builder().sessionId("s2").toolName("t2").question("?").build();
        PendingApproval pa3 = PendingApproval.builder().sessionId("s1").toolName("t3").question("?").build();

        store.createPending(pa1);
        String id2 = store.createPending(pa2);
        store.createPending(pa3);
        store.approve(id2, "admin", "ok"); // s2 已批准不在 pending 中

        List<PendingApproval> s1Pending = store.getPendingBySession("s1");
        assertEquals(2, s1Pending.size());

        List<PendingApproval> s2Pending = store.getPendingBySession("s2");
        assertEquals(0, s2Pending.size(), "Approved should not appear in pending");

        List<PendingApproval> s3Pending = store.getPendingBySession("s3");
        assertTrue(s3Pending.isEmpty());
    }

    // ═══════════════════════════════════════════════════════════
    // getAll + getAllPending
    // ═══════════════════════════════════════════════════════════

    @Test
    void getAllReturnsAllIncludingResolved() {
        PendingApproval pa1 = PendingApproval.builder().sessionId("s1").toolName("t1").question("?").build();
        String id1 = store.createPending(pa1);
        store.approve(id1, "admin", "ok");

        List<PendingApproval> all = store.getAll();
        assertEquals(1, all.size());
    }

    @Test
    void getAllPendingReturnsOnlyPending() {
        PendingApproval pa1 = PendingApproval.builder().sessionId("s1").toolName("t1").question("?").build();
        String id1 = store.createPending(pa1);
        store.approve(id1, "admin", "ok");

        PendingApproval pa2 = PendingApproval.builder().sessionId("s2").toolName("t2").question("?").build();
        store.createPending(pa2);

        List<PendingApproval> pending = store.getAllPending();
        assertEquals(1, pending.size());
        assertEquals("t2", pending.get(0).getToolName());
    }

    // ═══════════════════════════════════════════════════════════
    // isApproved handles expired bypass keys
    // ═══════════════════════════════════════════════════════════

    @Test
    void isApprovedReturnsFalseForExpiredApproval() {
        PendingApproval pa = PendingApproval.builder()
            .sessionId("s1").toolName("exp_tool").question("?")
            .expiresAt(System.currentTimeMillis() - 1000).build();
        String id = store.createPending(pa);
        store.approve(id, "admin", "ok");

        // isApproved should detect expired state
        assertFalse(store.isApproved("s1", "exp_tool"),
            "Expired approved key should return false and clean up");
    }

    // ═══════════════════════════════════════════════════════════
    // 并发安全
    // ═══════════════════════════════════════════════════════════

    @Test
    void concurrentCreateAndApproveIsSafe() throws Exception {
        int count = 100;
        String[] ids = new String[count];
        for (int i = 0; i < count; i++) {
            PendingApproval pa = PendingApproval.builder()
                .sessionId("s-" + (i % 10)).toolName("tool-" + i).question("?").build();
            ids[i] = store.createPending(pa);
        }

        CountDownLatch latch = new CountDownLatch(count);
        AtomicInteger approved = new AtomicInteger(0);
        AtomicInteger denied = new AtomicInteger(0);

        for (int i = 0; i < count; i++) {
            final int idx = i;
            new Thread(() -> {
                if (idx % 2 == 0) {
                    store.approve(ids[idx], "admin", "ok");
                    approved.incrementAndGet();
                } else {
                    store.deny(ids[idx], "admin", "no");
                    denied.incrementAndGet();
                }
                latch.countDown();
            }).start();
        }

        latch.await();

        assertEquals(count, approved.get() + denied.get());
        List<PendingApproval> all = store.getAll();
        assertEquals(count, all.size());
    }
}
