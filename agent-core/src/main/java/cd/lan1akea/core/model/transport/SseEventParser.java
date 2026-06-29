package cd.lan1akea.core.model.transport;

import cd.lan1akea.core.model.ChatStreamChunk;
import cd.lan1akea.core.util.JsonUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SSE（Server-Sent Events）事件解析器。
 * 将 SSE 文本行流解析为 ChatStreamChunk 流。
 * 支持 OpenAI 兼容的 SSE 格式（OpenAI / DeepSeek / DashScope 等）。
 */
public class SseEventParser {

    /**
     * 解析 SSE 行流为 ChatStreamChunk 流。
     */
    public Flux<ChatStreamChunk> parse(Flux<String> sseLines) {
        // 每次订阅创建独立的 index→id 映射，保证并发安全
        return Flux.defer(() -> {
            Map<Integer, String> indexToId = new HashMap<>();
            return sseLines
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).trim())
                .filter(data -> !"[DONE]".equals(data))
                .flatMapIterable(json -> parseChunks(json, indexToId));
        });
    }

    @SuppressWarnings("unchecked")
    private List<ChatStreamChunk> parseChunks(String json, Map<Integer, String> indexToId) {
        try {
            Map<String, Object> map = JsonUtils.fromJson(json, Map.class);
            if (map == null) return List.of();

            List<ChatStreamChunk> chunks = new ArrayList<>();
            java.util.List<Map<String, Object>> choices =
                (java.util.List<Map<String, Object>>) map.get("choices");
            if (choices == null || choices.isEmpty()) return chunks;

            Map<String, Object> choice = choices.get(0);
            String finishReason = (String) choice.get("finish_reason");
            Map<String, Object> delta = (Map<String, Object>) choice.get("delta");
            if (delta == null) {
                if (finishReason != null)
                    chunks.add(ChatStreamChunk.builder().finishReason(finishReason).build());
                return chunks;
            }

            // 推理/思考内容
            Object reasoningContent = delta.get("reasoning_content");
            Object content = delta.get("content");
            if (reasoningContent != null && !reasoningContent.toString().isEmpty()) {
                chunks.add(ChatStreamChunk.builder()
                    .delta(reasoningContent.toString()).type(ChatStreamChunk.TYPE_THINKING)
                    .finishReason(finishReason).build());
            } else if (content != null && !content.toString().isEmpty()) {
                chunks.add(ChatStreamChunk.builder()
                    .delta(content.toString()).type(ChatStreamChunk.TYPE_TEXT)
                    .finishReason(finishReason).build());
            }

            // 工具调用 — index 映射 id，适配 OpenAI/DeepSeek 等多种协议
            List<Map<String, Object>> toolCalls =
                (List<Map<String, Object>>) delta.get("tool_calls");
            if (toolCalls != null) {
                for (Map<String, Object> tc : toolCalls) {
                    int index = ((Number) tc.getOrDefault("index", 0)).intValue();
                    Map<String, Object> func = (Map<String, Object>) tc.get("function");
                    if (func == null) continue;

                    String fName = (String) func.get("name");
                    String fArgs = (String) func.get("arguments");
                    boolean hasName = fName != null;
                    boolean hasArgs = fArgs != null && !fArgs.isEmpty();

                    // 用 index 映射：新 id → 记录，无 id → 回查
                    String tcId = (String) tc.get("id");
                    if (tcId != null) indexToId.put(index, tcId);
                    else if (!indexToId.containsKey(index)) continue; // 无 id 且无史
                    tcId = indexToId.get(index);

                    if (hasName) {
                        chunks.add(ChatStreamChunk.builder()
                            .type(ChatStreamChunk.TYPE_TOOL_USE_START)
                            .toolUseId(tcId).toolName(fName).index(index)
                            .finishReason(finishReason).build());
                    }
                    if (hasArgs) {
                        chunks.add(ChatStreamChunk.builder()
                            .type(ChatStreamChunk.TYPE_TOOL_USE_DELTA)
                            .toolUseId(tcId).delta(fArgs).index(index)
                            .finishReason(finishReason).build());
                    }
                }
            }

            if (chunks.isEmpty() && finishReason != null)
                chunks.add(ChatStreamChunk.builder().finishReason(finishReason).build());
            return chunks;
        } catch (Exception e) {
            return List.of();
        }
    }
}
