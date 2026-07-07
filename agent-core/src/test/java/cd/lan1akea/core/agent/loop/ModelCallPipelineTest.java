package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.FinishReason;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.ChatStreamChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelCallPipelineTest {

    @Test
    void assembleResponse_noFinishReason_shouldDefaultToCompleted() {
        List<ChatStreamChunk> chunks = List.of(
                ChatStreamChunk.builder().delta("hello").type(ChatStreamChunk.TYPE_TEXT).build()
        );
        ChatResponse resp = ModelCallPipeline.assembleResponseFromChunks(chunks);
        assertNotNull(resp);
        assertEquals(FinishReason.COMPLETED, resp.getFinishReason());
    }
}
