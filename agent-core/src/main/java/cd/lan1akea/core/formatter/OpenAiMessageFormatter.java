package cd.lan1akea.core.formatter;

import cd.lan1akea.core.message.*;

import java.util.*;

/**
 * OpenAI 兼容消息格式化器。
 * 输出格式: [{"role": "...", "content": "..." | [...]}, ...]。
 * 支持 system、user、assistant、tool 四种角色。
 */
public class OpenAiMessageFormatter implements MessageFormatter {

    /**
     * 将消息列表格式化为 OpenAI 兼容的 API 请求体。
     */
    @Override
    public List<Map<String, Object>> format(List<Msg> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Msg msg : messages) {
            result.add(formatSingle(msg));
        }
        return result;
    }

    /**
     * 格式化单条消息为 OpenAI 兼容的 Map。
     *
     * @param msg 待格式化的消息
     * @return 格式化后的 Map，包含 role 和 content 字段
     */
    protected Map<String, Object> formatSingle(Msg msg) {
        Map<String, Object> formatted = new LinkedHashMap<>();
        formatted.put("role", msg.getRole().getValue());

        // 处理内容
        List<ContentBlock> blocks = msg.getContentBlocks();
        if (blocks.isEmpty()) {
            formatted.put("content", "");
        } else if (blocks.size() == 1 && blocks.get(0) instanceof TextBlock) {
            formatted.put("content", ((TextBlock) blocks.get(0)).getText());
        } else {
            // 多模态内容 → content 数组
            List<Map<String, Object>> content = new ArrayList<>();
            for (ContentBlock block : blocks) {
                if (block instanceof TextBlock) {
                    Map<String, Object> textPart = new LinkedHashMap<>();
                    textPart.put("type", "text");
                    textPart.put("text", ((TextBlock) block).getText());
                    content.add(textPart);
                } else if (block instanceof ImageBlock) {
                    content.add(formatImage((ImageBlock) block));
                } else if (block instanceof ToolUseBlock) {
                    content.add(formatToolUse((ToolUseBlock) block));
                } else if (block instanceof ToolResultBlock) {
                    content.add(formatToolResult((ToolResultBlock) block));
                }
            }
            if (content.size() == 1) {
                formatted.put("content", content.get(0).get("text"));
            } else {
                formatted.put("content", content);
            }
        }
        return formatted;
    }

    /**
     * 将图片块格式化为 OpenAI image_url 格式。
     *
     * @param image 图片内容块
     * @return OpenAI 兼容的图片表示
     */
    private Map<String, Object> formatImage(ImageBlock image) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "image_url");
        Map<String, Object> imageUrl = new LinkedHashMap<>();
        if (image.getSource() instanceof UrlSource) {
            imageUrl.put("url", ((UrlSource) image.getSource()).getUrl());
        }
        part.put("image_url", imageUrl);
        return part;
    }

    /**
     * 将工具调用块格式化为 OpenAI tool_use 格式。
     *
     * @param toolUse 工具调用内容块
     * @return OpenAI 兼容的工具调用表示
     */
    private Map<String, Object> formatToolUse(ToolUseBlock toolUse) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "tool_use");
        part.put("id", toolUse.getId());
        part.put("name", toolUse.getName());
        part.put("input", toolUse.getArguments());
        return part;
    }

    /**
     * 将工具结果块格式化为 OpenAI tool_result 格式。
     *
     * @param toolResult 工具结果内容块
     * @return OpenAI 兼容的工具结果表示
     */
    private Map<String, Object> formatToolResult(ToolResultBlock toolResult) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "tool_result");
        part.put("tool_use_id", toolResult.getToolUseId());
        part.put("content", toolResult.getContent());
        return part;
    }
}
