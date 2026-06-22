package cd.lan1akea.core.hook.impl;

import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.Msg;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 内容过滤 Hook。
 * <p>
 * 在 LLM 返回后检查输出内容，过滤敏感词或注入安全检查。
 * 实现 PostReasoningHook，在推理完成后审查输出。
 * </p>
 */
public class ContentFilterHook implements PostReasoningHook, InterruptHook {

    private final String name;
    private final List<String> blockedWords;

    public ContentFilterHook(String name, List<String> blockedWords) {
        this.name = name;
        this.blockedWords = blockedWords != null ? blockedWords : List.of();
    }

    public ContentFilterHook() {
        this("ContentFilterHook", List.of());
    }

    @Override
    public String getName() { return name; }

    @Override
    public HookEventType getSubscribedEventType() {
        return HookEventType.POST_REASONING;
    }

    @Override
    public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        if (event instanceof ReasoningEvent) {
            ReasoningEvent re = (ReasoningEvent) event;
            List<Msg> messages = re.getMessages();
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
        }
        return Mono.just(HookResult.continue_());
    }

    @Override
    public int getPriority() { return 50; } // 高优先级，在其他 Hook 之前执行
}
