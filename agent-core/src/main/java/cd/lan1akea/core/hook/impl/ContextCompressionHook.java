package cd.lan1akea.core.hook.impl;

import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.MsgRole;
import cd.lan1akea.core.message.SystemMessage;
import cd.lan1akea.core.model.ModelContextWindow;
import cd.lan1akea.core.session.SessionSummaryService;
import cd.lan1akea.core.session.ChatTurn;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 上下文压缩 Hook（PreReasoningHook）。
 * <p>
 * LLM 推理前检查 Token 使用量，超过窗口 75% 时自动将早期对话压缩为摘要。
 * 通过 Hook 机制实现，可按需注册/移除/替换。
 * </p>
 */
public class ContextCompressionHook implements PreReasoningHook {

    private final String name;
    private final SessionSummaryService summaryService;
    private final ModelContextWindow contextWindow;
    private final double threshold; // 触发压缩的使用率阈值

    public ContextCompressionHook(SessionSummaryService summaryService,
                                   ModelContextWindow contextWindow) {
        this("ContextCompression", summaryService, contextWindow, 0.75);
    }

    public ContextCompressionHook(String name, SessionSummaryService summaryService,
                                   ModelContextWindow contextWindow, double threshold) {
        this.name = name;
        this.summaryService = summaryService;
        this.contextWindow = contextWindow;
        this.threshold = threshold;
    }

    @Override
    public String getName() { return name; }

    @Override
    public HookEventType getSubscribedEventType() { return HookEventType.PRE_REASONING; }

    @Override
    public int getPriority() { return 5; } // 最先执行（压缩后再做其他处理）

    @Override
    public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        if (!(event instanceof ReasoningEvent)) return Mono.just(HookResult.continue_());
        ReasoningEvent re = (ReasoningEvent) event;
        List<Msg> messages = re.getMessages();
        if (messages == null || messages.size() <= 4) return Mono.just(HookResult.continue_());

        // 估算 Token 数（简单按字符数/2估算）
        long estimatedTokens = messages.stream()
            .mapToLong(m -> m.getTextContent().length()).sum() / 2;
        double usage = (double) estimatedTokens / contextWindow.getMaxInputTokens();

        if (usage < threshold) return Mono.just(HookResult.continue_());

        // 执行压缩：保留最近 4 条，其余压缩为摘要
        int keep = 4;
        int removeCount = messages.size() - keep;
        List<Msg> oldMsgs = new ArrayList<>(messages.subList(0, removeCount));
        messages.subList(0, removeCount).clear();

        List<ChatTurn> turns = new ArrayList<>();
        for (int i = 0; i < oldMsgs.size(); i += 2) {
            String u = i < oldMsgs.size() ? oldMsgs.get(i).getTextContent() : "";
            String a = i + 1 < oldMsgs.size() ? oldMsgs.get(i + 1).getTextContent() : "";
            turns.add(new ChatTurn(0, 0, i / 2, u, a, null, LocalDateTime.now()));
        }

        Msg summary = summaryService.summarize(turns);
        messages.add(0, summary);

        return Mono.just(HookResult.modify("压缩了 " + removeCount + " 条消息为摘要"));
    }
}
