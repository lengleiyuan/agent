package cd.lan1akea.core.approval;

import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.Msg;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 审批 Hook。监听 {@link HookEventType#ON_INTERRUPT}，
 * 从 {@link InterruptEvent} 中提取上下文创建 {@link PendingApproval} 并存入 {@link ApprovalStore}。
 */
public class ApprovalHook implements Hook {

    private final ApprovalStore store;

    public ApprovalHook(ApprovalStore store) {
        this.store = store;
    }

    @Override
    public String getName() { return "approval"; }

    @Override
    public Set<HookEventType> getSubscribedEventTypes() {
        return Set.of(HookEventType.ON_INTERRUPT);
    }

    @Override
    public int getPriority() { return 20; }

    @Override
    @SuppressWarnings("unchecked")
    public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        if (!(event instanceof InterruptEvent ie)) return Mono.just(HookResult.continue_());
        if (ie.getToolName() == null) return Mono.just(HookResult.continue_());

        Map<String, Object> arguments = ie.getPayload("arguments");
        String toolDescription = ie.getPayload("toolDescription");
        List<Msg> recentMessages = ie.getPayload("recentMessages");
        String riskLevel = ie.getPayload("riskLevel");
        if (riskLevel == null) riskLevel = "MEDIUM";

        PendingApproval pa = PendingApproval.builder()
            .sessionId(context.getSessionId())
            .requesterId(context.getUserId())
            .agentName(context.getAgentName())
            .toolName(ie.getToolName())
            .toolDescription(toolDescription)
            .arguments(arguments)
            .question(ie.getReason())
            .riskLevel(riskLevel)
            .recentMessages(recentMessages)
            .status(PendingApproval.Status.PENDING)
            .build();

        store.createPending(pa);
        return Mono.just(HookResult.continue_());
    }
}
