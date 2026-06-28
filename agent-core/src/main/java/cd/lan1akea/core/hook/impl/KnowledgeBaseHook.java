package cd.lan1akea.core.hook.impl;

import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.knowledge.KnowledgeMatcher;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.MsgRole;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

/**
 * 知识库拦截 Hook。PRE_REASONING 阶段匹配用户问题，命中则绕过模型直接返回。
 */
public class KnowledgeBaseHook implements Hook {

    private final String name;
    private final KnowledgeMatcher matcher;

    public KnowledgeBaseHook(KnowledgeMatcher matcher) {
        this("KnowledgeBaseHook", matcher);
    }

    public KnowledgeBaseHook(String name, KnowledgeMatcher matcher) {
        this.name = name;
        this.matcher = matcher;
    }

    @Override public String getName() { return name; }

    @Override
    public Set<HookEventType> getSubscribedEventTypes() {
        return Set.of(HookEventType.PRE_REASONING);
    }

    @Override public int getPriority() { return 90; }

    @Override
    public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        if (!(event instanceof ReasoningEvent re) || matcher == null)
            return Mono.just(HookResult.continue_());

        String userInput = extractLastUserMessage(re.getMessages());
        if (userInput == null) return Mono.just(HookResult.continue_());

        String answer = matcher.match(userInput);
        if (answer != null) {
            Msg reply = Msg.builder(MsgRole.ASSISTANT).addText(answer).build();
            re.setBypassMessage(reply);
        }
        return Mono.just(HookResult.continue_());
    }

    private String extractLastUserMessage(List<Msg> messages) {
        if (messages == null) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == MsgRole.USER) {
                return messages.get(i).getTextContent();
            }
        }
        return null;
    }
}
