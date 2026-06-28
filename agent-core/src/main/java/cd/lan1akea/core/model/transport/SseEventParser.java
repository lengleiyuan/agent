package cd.lan1akea.core.model.transport;

import cd.lan1akea.core.model.ChatStreamChunk;
import cd.lan1akea.core.util.JsonUtils;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * SSE（Server-Sent Events）事件解析器。
 * 将 SSE 文本行流解析为 ChatStreamChunk 流。
 * 支持 OpenAI 兼容的 SSE 格式。
 */
public class SseEventParser {

    /**
     * 解析 SSE 行流为 ChatStreamChunk 流。
     *
     * @param sseLines SSE 原始行流
     * @return Flux&lt;ChatStreamChunk&gt;
     */
    public Flux<ChatStreamChunk> parse(Flux<String> sseLines) {
        return sseLines
            .filter(line -> line.startsWith("data:"))
            .map(line -> line.substring(5).trim())
            .filter(data -> !"[DONE]".equals(data))
            .handle((json, sink) -> {
                ChatStreamChunk chunk = parseChunk(json);
                if (chunk != null) {
                    sink.next(chunk);
                }
            });
    }

    /**
     * 解析单条 SSE data JSON 为 ChatStreamChunk。
     *
     * @param json SSE data 中的 JSON 字符串
     * @return 解析后的 chunk，解析失败返回 null
     */
    @SuppressWarnings("unchecked")
    private ChatStreamChunk parseChunk(String json) {
        try {
            Map<String, Object> map = JsonUtils.fromJson(json, Map.class);
            if (map == null) {
                return null;
            }

            ChatStreamChunk.Builder builder = ChatStreamChunk.builder();

            // 解析 choices
            java.util.List<Map<String, Object>> choices =
                (java.util.List<Map<String, Object>>) map.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> choice = choices.get(0);
                Map<String, Object> delta = (Map<String, Object>) choice.get("delta");

                if (delta != null) {
                    // 推理/思考内容优先 (DeepSeek R1, Claude thinking, etc.)
                    Object reasoningContent = delta.get("reasoning_content");
                    Object content = delta.get("content");

                    if (reasoningContent != null && !reasoningContent.toString().isEmpty()) {
                        builder.delta(reasoningContent.toString())
                            .type(ChatStreamChunk.TYPE_THINKING);
                    } else if (content != null && !content.toString().isEmpty()) {
                        builder.delta(content.toString())
                            .type(ChatStreamChunk.TYPE_TEXT);
                    }

                    // 工具调用
                    java.util.List<Map<String, Object>> toolCalls =
                        (java.util.List<Map<String, Object>>) delta.get("tool_calls");
                    if (toolCalls != null && !toolCalls.isEmpty()) {
                        Map<String, Object> tc = toolCalls.get(0);
                        String tcId = (String) tc.get("id");
                        Map<String, Object> func = (Map<String, Object>) tc.get("function");
                        if (func != null) {
                            String fName = (String) func.get("name");
                            String fArgs = (String) func.get("arguments");
                            if (fName != null) {
                                builder.type(ChatStreamChunk.TYPE_TOOL_USE_START)
                                    .toolUseId(tcId)
                                    .toolName(fName);
                            } else if (fArgs != null) {
                                builder.type(ChatStreamChunk.TYPE_TOOL_USE_DELTA)
                                    .toolUseId(tcId)
                                    .delta(fArgs);
                            }
                        }
                    }
                }

                // 完成原因
                String finishReason = (String) choice.get("finish_reason");
                if (finishReason != null) {
                    builder.finishReason(finishReason);
                }
            }

            return builder.build();
        } catch (Exception e) {
            return null; // 忽略解析失败的 chunk
        }
    }
}
