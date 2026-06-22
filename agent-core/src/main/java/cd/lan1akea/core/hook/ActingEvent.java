package cd.lan1akea.core.hook;

import cd.lan1akea.core.message.ToolUseBlock;

import java.util.Collections;
import java.util.List;

/**
 * 行动事件。
 * <p>
 * 在 PRE_ACTING 或 POST_ACTING 阶段携带待执行的工具调用列表。
 * </p>
 */
public class ActingEvent extends HookEvent {

    public ActingEvent(HookEventType eventType) {
        super(eventType);
    }

    /** 设置工具调用列表 */
    public void setToolCalls(List<ToolUseBlock> toolCalls) {
        setPayload("toolCalls", toolCalls);
    }

    /** 获取工具调用列表 */
    @SuppressWarnings("unchecked")
    public List<ToolUseBlock> getToolCalls() {
        List<ToolUseBlock> calls = getPayload("toolCalls");
        return calls != null ? calls : Collections.emptyList();
    }
}
