package cd.lan1akea.core.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryMemoryTest {

    private InMemoryMemory memory;

    @BeforeEach
    void setUp() {
        memory = new InMemoryMemory();
    }

    private static MemoryEntry entry(String id, String content, long tenantId, Long userId) {
        return new MemoryEntry(id, tenantId, userId, content, null, null, LocalDateTime.now());
    }

    @Test
    void testStoreAndRetrieve() {
        memory.store(entry("id1", "hello world", 100L, 10L)).block();

        var results = memory.retrieve(new MemoryRetrievalQuery("hello", 10, null, null))
            .collectList().block();

        assertEquals(1, results.size());
        assertEquals("id1", results.get(0).getId());
    }

    @Test
    void testRetrieveFiltersByTenant() {
        memory.store(entry("id1", "data", 100L, 10L)).block();
        memory.store(entry("id2", "data", 200L, 20L)).block();

        var results = memory.retrieve(new MemoryRetrievalQuery("data", 10, 100L, null))
            .collectList().block();

        assertEquals(1, results.size());
        assertEquals(100L, results.get(0).getTenantId());
    }

    @Test
    void testRetrieveFiltersByUser() {
        memory.store(entry("id1", "data", 100L, 10L)).block();
        memory.store(entry("id2", "data", 100L, 20L)).block();

        var results = memory.retrieve(new MemoryRetrievalQuery("data", 10, null, 10L))
            .collectList().block();

        assertEquals(1, results.size());
        assertEquals(10L, results.get(0).getUserId());
    }

    @Test
    void testRetrieveByKeyword() {
        memory.store(entry("id1", "apple pie recipe", 100L, 10L)).block();
        memory.store(entry("id2", "banana bread recipe", 100L, 10L)).block();

        var results = memory.retrieve(new MemoryRetrievalQuery("apple", 10, null, null))
            .collectList().block();

        assertEquals(1, results.size());
        assertEquals("id1", results.get(0).getId());
    }

    @Test
    void testRetrieveRespectsMaxResults() {
        memory.store(entry("id1", "data A", 100L, null)).block();
        memory.store(entry("id2", "data B", 100L, null)).block();
        memory.store(entry("id3", "data C", 100L, null)).block();

        var results = memory.retrieve(new MemoryRetrievalQuery("data", 2, null, null))
            .collectList().block();

        assertTrue(results.size() <= 2);
    }

    @Test
    void testRetrieveEmptyQueryReturnsAllMatching() {
        memory.store(entry("id1", "data", 100L, null)).block();
        memory.store(entry("id2", "data", 100L, null)).block();

        var results = memory.retrieve(new MemoryRetrievalQuery(null, 10, null, null))
            .collectList().block();

        assertEquals(2, results.size());
    }

    @Test
    void testForget() {
        memory.store(entry("id1", "data", 100L, null)).block();
        memory.forget("id1").block();

        var results = memory.retrieve(new MemoryRetrievalQuery("data", 10, null, null))
            .collectList().block();
        assertTrue(results.isEmpty());
    }

    @Test
    void testForgetNonExistent() {
        memory.forget("nonexistent").block(); // should not throw
    }

    @Test
    void testClear() {
        memory.store(entry("id1", "data", 100L, null)).block();
        memory.store(entry("id2", "data", 100L, null)).block();
        memory.clear().block();

        var results = memory.retrieve(new MemoryRetrievalQuery(null, 10, null, null))
            .collectList().block();
        assertTrue(results.isEmpty());
    }

    @Test
    void testStoreOverwritesExisting() {
        memory.store(entry("id1", "old", 100L, null)).block();
        memory.store(entry("id1", "new", 100L, null)).block();

        var results = memory.retrieve(new MemoryRetrievalQuery("new", 10, null, null))
            .collectList().block();
        assertEquals(1, results.size());
        assertEquals("new", results.get(0).getContent());
    }
}
