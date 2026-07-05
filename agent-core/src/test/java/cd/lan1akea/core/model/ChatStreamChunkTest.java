package cd.lan1akea.core.model;

import cd.lan1akea.core.CoreConstants.FinishReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatStreamChunkTest {

    @Test
    void of_shouldSetDeltaAndFinishReason() {
        ChatStreamChunk chunk = ChatStreamChunk.of("hello", FinishReason.STOP);

        assertEquals("hello", chunk.getDelta());
        assertEquals(FinishReason.STOP, chunk.getFinishReason());
    }

    @Test
    void of_shouldDefaultToTextType() {
        ChatStreamChunk chunk = ChatStreamChunk.of("text", null);

        assertEquals(ChatStreamChunk.TYPE_TEXT, chunk.getType());
    }

    @Test
    void of_nullText_shouldPreserveNull() {
        ChatStreamChunk chunk = ChatStreamChunk.of(null, FinishReason.INTERRUPTED);

        assertNull(chunk.getDelta());
        assertEquals(FinishReason.INTERRUPTED, chunk.getFinishReason());
    }

    @Test
    void of_nullFinishReason_shouldPreserveNull() {
        ChatStreamChunk chunk = ChatStreamChunk.of("text", null);

        assertEquals("text", chunk.getDelta());
        assertNull(chunk.getFinishReason());
    }

    @Test
    void of_emptyText_shouldPreserveEmpty() {
        ChatStreamChunk chunk = ChatStreamChunk.of("", FinishReason.STOP);

        assertEquals("", chunk.getDelta());
    }
}
