package cd.lan1akea.core.memory;

import java.util.Collections;
import java.util.List;

/**
 * 记忆检索结果。
 */
public class MemoryRetrievalResult {

    private final List<MemoryEntry> entries;
    private final int totalFound;

    public MemoryRetrievalResult(List<MemoryEntry> entries, int totalFound) {
        this.entries = entries != null ? Collections.unmodifiableList(entries) : Collections.emptyList();
        this.totalFound = totalFound;
    }

    public List<MemoryEntry> getEntries() { return entries; }
    public int getTotalFound() { return totalFound; }
    public boolean isEmpty() { return entries.isEmpty(); }
}
