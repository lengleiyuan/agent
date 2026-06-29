package cd.lan1akea.harness.hook;

import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.MsgRole;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.function.BiFunction;

/**
 * 知识库拦截 Hook（门面层，可选注入）。
 * PRE_REASONING 阶段匹配用户输入，命中则绕过模型直接返回预设答案。
 *
 * <p>使用示例：
 * <pre>{@code
 * ConcurrentHashMap<String, String> kb = new ConcurrentHashMap<>();
 * kb.put("你好", "你好！有什么可以帮您？");
 * HarnessAgent.builder()
 *     .hook(new KnowledgeBaseHook((query, ctx) -> kb.get(query)))
 *     .build();
 * }</pre>
 */
public class KnowledgeBaseHook implements IHook {

    private final String name;
    private final BiFunction<String, HookContext, String> matcher;

    public KnowledgeBaseHook(BiFunction<String, HookContext, String> matcher) {
        this("KnowledgeBaseHook", matcher);
    }

    public KnowledgeBaseHook(String name, BiFunction<String, HookContext, String> matcher) {
        this.name = name;
        this.matcher = matcher;
    }

    @Override public String getName() { return name; }
    @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(HookEventType.PRE_REASONING); }
    @Override public int getPriority() { return 90; }

    @Override
    public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        if (!(event instanceof ReasoningEvent re) || matcher == null)
            return Mono.just(HookResult.continue_());

        String input = extractLastUserMessage(re.getMessages());
        if (input == null) return Mono.just(HookResult.continue_());

        try {
            String answer = matcher.apply(input, context);
            if (answer != null) {
                Msg reply = Msg.builder(MsgRole.ASSISTANT).addText(answer).build();
                re.setBypassMessage(reply);
            }
        } catch (Exception e) {
            return Mono.just(HookResult.abort("KB 匹配异常: " + e.getMessage()));
        }
        return Mono.just(HookResult.continue_());
    }

    private String extractLastUserMessage(java.util.List<Msg> messages) {
        if (messages == null) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == MsgRole.USER)
                return messages.get(i).getTextContent();
        }
        return null;
    }
}
