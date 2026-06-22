package cd.lan1akea.core.hook.impl;

import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.memory.Memory;
import cd.lan1akea.core.memory.MemoryEntry;
import cd.lan1akea.core.memory.MemoryRetrievalQuery;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.MsgRole;
import cd.lan1akea.core.message.SystemMessage;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * 记忆检索 Hook（PreReasoningHook）。
 * <p>
 * LLM 推理前从长期记忆中检索与当前对话相关的上下文，
 * 以系统消息形式注入到消息列表顶部。
 * 通过 Hook 机制实现，可按需移除，也可替换为知识库/Skill 等其他检索方式。
 * </p>
 */
public class MemoryEnrichmentHook implements PreReasoningHook {

    private final String name;
    private final Memory memory;
    private final int maxResults;

    public MemoryEnrichmentHook(Memory memory) {
        this("MemoryEnrichment", memory, 3);
    }

    public MemoryEnrichmentHook(String name, Memory memory, int maxResults) {
        this.name = name;
        this.memory = memory;
        this.maxResults = maxResults;
    }

    @Override
    public String getName() { return name; }

    @Override
    public HookEventType getSubscribedEventType() { return HookEventType.PRE_REASONING; }

    @Override
    public int getPriority() { return 10; } // 压缩之后、其他处理之前

    @Override
    public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        if (!(event instanceof ReasoningEvent) || memory == null)
            return Mono.just(HookResult.continue_());

        ReasoningEvent re = (ReasoningEvent) event;
        List<Msg> messages = re.getMessages();
        if (messages == null || messages.isEmpty()) return Mono.just(HookResult.continue_());

        // 取最后一条用户消息作为检索查询
        String query = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == MsgRole.USER) {
                query = messages.get(i).getTextContent();
                break;
            }
        }
        if (query.isEmpty()) return Mono.just(HookResult.continue_());

        String tenantId = context != null ? context.getTenantId() : null;
        String userId = context != null ? context.getUserId() : null;
        Long tid = tenantId != null ? Long.parseLong(tenantId) : null;
        Long uid = userId != null ? Long.parseLong(userId) : null;

        try {
            List<MemoryEntry> entries = memory.retrieve(
                new MemoryRetrievalQuery(query, maxResults, tid, uid))
                .collectList().block(Duration.ofSeconds(5));
            if (entries != null && !entries.isEmpty()) {
                StringBuilder sb = new StringBuilder("相关记忆:\n");
                for (MemoryEntry e : entries) {
                    sb.append("- ").append(e.getContent()).append("\n");
                }
                messages.add(0, SystemMessage.of(sb.toString()));
                return Mono.just(HookResult.modify("注入了 " + entries.size() + " 条相关记忆"));
            }
        } catch (Exception ignored) {}

        return Mono.just(HookResult.continue_());
    }
}
