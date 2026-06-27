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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 记忆检索 Hook（PreReasoningHook）。
 *
 * LLM 推理前从长期记忆中检索与当前对话相关的上下文，
 * 以系统消息形式注入到消息列表顶部。
 * 通过 Hook 机制实现，可按需移除，也可替换为知识库/Skill 等其他检索方式。
 */
public class MemoryEnrichmentHook implements Hook {

    /**
     * Hook 名称
     */
    private final String name;
    /**
     * 长期存储器
     */
    private final Memory memory;
    /**
     * 最大检索结果数
     */
    private final int maxResults;

    /**
     * 创建记忆检索 Hook（默认参数：最多 3 条）。
     */
    public MemoryEnrichmentHook(Memory memory) {
        this("MemoryEnrichment", memory, 3);
    }

    /**
     * 创建记忆检索 Hook。
     */
    public MemoryEnrichmentHook(String name, Memory memory, int maxResults) {
        this.name = name;
        this.memory = memory;
        this.maxResults = maxResults;
    }

    /**
     * @return Hook 名称
     */
    @Override
    public String getName() { return name; }

    /**
     * @return PRE_REASONING 事件类型
     */
    @Override
    public Set<HookEventType> getSubscribedEventTypes() { return Set.of(HookEventType.PRE_REASONING); }

    /**
     * 优先级 10（压缩之后、其他处理之前）。
     */
    @Override
    public int getPriority() { return 10; }

    /**
     * 从长期记忆中检索并注入相关上下文。
     */
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

        try {
            List<MemoryEntry> entries = memory.retrieve(
                new MemoryRetrievalQuery(query, maxResults, tenantId, userId))
                .collectList().block(Duration.ofSeconds(5));
            if (entries != null && !entries.isEmpty()) {
                StringBuilder sb = new StringBuilder("相关记忆:\n");
                for (MemoryEntry e : entries) {
                    sb.append("- ").append(e.getContent()).append("\n");
                }
                // 创建新列表而非直接修改传入列表，保证线程安全
                List<Msg> enriched = new ArrayList<>(messages.size() + 1);
                enriched.add(SystemMessage.of(sb.toString()));
                enriched.addAll(messages);
                re.setMessages(enriched);
                return Mono.just(HookResult.modify("注入了 " + entries.size() + " 条相关记忆"));
            }
        } catch (Exception ignored) {}

        return Mono.just(HookResult.continue_());
    }
}
