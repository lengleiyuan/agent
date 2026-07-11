package cd.lan1akea.core.hook.impl;

import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.Msg;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

/**
 * 内容过滤 Hook。
 *
 * 在 LLM 返回后检查输出内容，过滤敏感词或注入安全检查。
 * 实现 PostReasoningHook，在推理完成后审查输出。
 */
public class ContentFilterHook implements Hook {

    /**
     * Hook 名称
     */
    private final String name;
    /**
     * 敏感词列表
     */
    private final List<String> blockedWords;

    /**
     * 创建内容过滤 Hook。
     */
    public ContentFilterHook(String name, List<String> blockedWords) {
        this.name = name;
        this.blockedWords = blockedWords != null ? blockedWords : List.of();
    }

    /**
     * 创建默认内容过滤 Hook（无敏感词）。
     */
    public ContentFilterHook() {
        this("ContentFilterHook", List.of());
    }

    /**
     * @return Hook 名称
     */
    @Override
    public String getName() { return name; }

    /**
     * @return POST_REASONING 和 ON_INTERRUPT 事件类型
     */
    @Override
    public Set<HookEventType> getSubscribedEventTypes() {
        return Set.of(HookEventType.POST_REASONING, HookEventType.ON_INTERRUPT);
    }

    /**
     * 检查消息内容是否包含敏感词，如有则终止执行。
     */
    @Override
    public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        List<Msg> messages = event.getMessages();
        if (messages != null) {
            for (Msg msg : messages) {
                String text = msg.getTextContent();
                for (String blocked : blockedWords) {
                    if (text != null && text.contains(blocked)) {
                        return Mono.just(HookResult.abort(
                            "内容包含敏感词: " + blocked));
                    }
                }
            }
        }
        return Mono.just(HookResult.continue_());
    }

    /**
     * 高优先级（50），在其他 Hook 之前执行。
     */
    @Override
    public int getPriority() { return 50; }
}
