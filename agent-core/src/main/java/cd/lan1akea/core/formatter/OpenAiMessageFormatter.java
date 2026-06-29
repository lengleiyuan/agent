package cd.lan1akea.core.formatter;

import cd.lan1akea.core.message.*;

import java.util.*;

/**
 * OpenAI 兼容消息格式化器。
 */
public class OpenAiMessageFormatter implements MessageFormatter {

    @Override
    public List<Map<String, Object>> format(List<Msg> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Msg msg : messages) result.add(formatSingle(msg));
        return result;
    }

    protected Map<String, Object> formatSingle(Msg msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", msg.getRole().getValue());

        List<ContentBlock> blocks = msg.getContentBlocks();
        if (blocks.isEmpty()) {
            m.put("content", "");
            return m;
        }

        // TOOL 角色 → 专用格式
        if (msg.getRole() == MsgRole.TOOL) {
            return formatToolMessage(msg, blocks);
        }

        // ASSISTANT 角色含 ToolUseBlock → tool_calls 字段
        List<ToolUseBlock> toolUses = msg.getToolUseBlocks();
        if (!toolUses.isEmpty()) {
            List<Map<String, Object>> tcs = new ArrayList<>();
            for (ToolUseBlock tc : toolUses) tcs.add(formatToolCall(tc));
            m.put("tool_calls", tcs);
            List<ContentBlock> textBlocks = blocks.stream().filter(b -> b instanceof TextBlock).toList();
            m.put("content", textBlocks.isEmpty() ? null : joinText(textBlocks));
            return m;
        }

        // 单个 TextBlock → 字符串
        if (blocks.size() == 1 && blocks.get(0) instanceof TextBlock tb) {
            m.put("content", tb.getText());
            return m;
        }

        // 多模态 → content 数组
        m.put("content", buildContentArray(blocks));
        return m;
    }

    // ── 可覆盖的扩展点 ──

    /**
     * 单个 ToolUseBlock → tool_calls 数组元素
     * */
    protected Map<String, Object> formatToolCall(ToolUseBlock tc) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", tc.getId());
        call.put("type", "function");
        Map<String, Object> func = new LinkedHashMap<>();
        func.put("name", tc.getName());
        func.put("arguments", tc.getArguments() != null ? tc.getArguments() : "{}");
        call.put("function", func);
        return call;
    }

    /**
     * TOOL 角色消息的格式化
     * */
    protected Map<String, Object> formatToolMessage(Msg msg, List<ContentBlock> blocks) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "tool");
        if (blocks.size() == 1 && blocks.get(0) instanceof ToolResultBlock tr) {
            m.put("tool_call_id", tr.getToolUseId());
            m.put("content", tr.getContent() != null ? tr.getContent() : "");
        } else {
            m.put("content", "");
        }
        return m;
    }

    /**
     * ToolResultBlock → content 数组元素
     * */
    protected Map<String, Object> formatToolResult(ToolResultBlock tr) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "tool_result");
        part.put("tool_use_id", tr.getToolUseId());
        part.put("content", tr.getContent());
        return part;
    }

    /**
     * ImageBlock → content 数组元素
     * */
    protected Map<String, Object> formatImage(ImageBlock image) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "image_url");
        Map<String, Object> url = new LinkedHashMap<>();
        if (image.getSource() instanceof UrlSource s) url.put("url", s.getUrl());
        part.put("image_url", url);
        return part;
    }

    // ── 内部工具方法 ──

    private List<Map<String, Object>> buildContentArray(List<ContentBlock> blocks) {
        List<Map<String, Object>> arr = new ArrayList<>();
        for (ContentBlock b : blocks) {
            if (b instanceof TextBlock tb) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("type", "text"); p.put("text", tb.getText());
                arr.add(p);
            } else if (b instanceof ImageBlock ib) {
                arr.add(formatImage(ib));
            } else if (b instanceof ToolResultBlock tr) {
                arr.add(formatToolResult(tr));
            }
        }
        return arr;
    }

    private static String joinText(List<ContentBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : blocks) if (b instanceof TextBlock tb) sb.append(tb.getText());
        return sb.toString();
    }
}
