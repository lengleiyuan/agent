package cd.lan1akea.core.util;

import cd.lan1akea.core.CoreConstants.FinishReason;
import cd.lan1akea.core.message.AssistantMessage;
import cd.lan1akea.core.message.ContentBlock;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.TextBlock;
import cd.lan1akea.core.message.ToolUseBlock;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.ChatStreamChunk;
import cd.lan1akea.core.model.ChatUsage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ChatResponse 工具方法。
 *
 * <p>提供从流式分块列表组装 ChatResponse 的纯函数。
 */
public final class ChatResponseUtil {

    private ChatResponseUtil() {}

    /**
     * 从流式分块列表组装单个 ChatResponse。
     *
     * <p>聚合文本增量（text）和工具调用分块（tool_use_start/delta）为完整响应。
     * 工具参数 JSON 经过 repairJson 修复常见 LLM 格式错误。
     *
     * @param chunks 流式分块列表
     * @return 组装后的聊天响应，chunks 为空时返回 null
     */
    public static ChatResponse fromChunks(List<ChatStreamChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return null;

        StringBuilder text = new StringBuilder();
        Map<String, String> toolArgs = new LinkedHashMap<>();
        Map<String, String> toolNames = new LinkedHashMap<>();

        for (ChatStreamChunk chunk : chunks) {
            if (chunk.getDelta() != null && ChatStreamChunk.TYPE_TEXT.equals(chunk.getType())) {
                text.append(chunk.getDelta());
            }
            if (ChatStreamChunk.TYPE_TOOL_USE_START.equals(chunk.getType())
                    && chunk.getToolUseId() != null) {
                toolNames.put(chunk.getToolUseId(),
                        chunk.getToolName() != null ? chunk.getToolName() : "");
                toolArgs.put(chunk.getToolUseId(), "");
            }
            if (ChatStreamChunk.TYPE_TOOL_USE_DELTA.equals(chunk.getType())
                    && chunk.getToolUseId() != null && chunk.getDelta() != null) {
                toolArgs.merge(chunk.getToolUseId(), chunk.getDelta(), String::concat);
            }
        }

        String finishReason = null;
        for (int i = chunks.size() - 1; i >= 0; i--) {
            if (chunks.get(i).getFinishReason() != null) {
                finishReason = chunks.get(i).getFinishReason();
                break;
            }
        }
        if (finishReason == null) finishReason = FinishReason.COMPLETED;

        List<ContentBlock> blocks = new ArrayList<>();
        if (!text.isEmpty()) {
            blocks.add(new TextBlock(text.toString()));
        }
        for (Map.Entry<String, String> e : toolArgs.entrySet()) {
            String id = e.getKey();
            blocks.add(new ToolUseBlock(id, toolNames.getOrDefault(id, ""),
                    JsonUtils.repairJson(e.getValue())));
        }

        ChatUsage usage = !chunks.isEmpty() ? chunks.get(chunks.size() - 1).getUsage() : null;
        if (usage == null) usage = new ChatUsage(0, 0);

        Msg msg = new AssistantMessage(blocks, null);
        return new ChatResponse(msg, usage, finishReason, null);
    }
}
