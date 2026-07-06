package cd.lan1akea.core.hook.impl;

import cd.lan1akea.core.agent.loop.LoopContext;
import cd.lan1akea.core.hook.Hook;
import cd.lan1akea.core.hook.HookEvent;
import cd.lan1akea.core.hook.HookContext;
import cd.lan1akea.core.hook.HookEventType;
import cd.lan1akea.core.hook.HookResult;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.MsgRole;
import cd.lan1akea.core.session.ChatTurn;
import cd.lan1akea.core.session.SessionId;
import cd.lan1akea.core.state.AgentState;
import cd.lan1akea.core.state.AgentStateStore;
import cd.lan1akea.core.util.IdGenerator;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会话持久化 Hook。
 * 监听 {@link HookEventType#AFTER_ITERATION}，每次 ReAct 迭代结束后将当前对话轮次和
 * 执行检查点写入 {@link AgentStateStore}。
 */
public class SessionPersistenceHook implements Hook {

    private final AgentStateStore stateStore;

    public SessionPersistenceHook(AgentStateStore stateStore) {
        this.stateStore = stateStore;
    }

    @Override
    public String getName() { return "session-persistence"; }

    @Override
    public Set<HookEventType> getSubscribedEventTypes() {
        return Set.of(HookEventType.AFTER_ITERATION);
    }

    @Override
    public int getPriority() { return Integer.MAX_VALUE; } // 最后执行

    @Override
    public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        LoopContext loopCtx = event.getPayload("loopContext");
        if (loopCtx == null || stateStore == null || context.getSessionId() == null) {
            return Mono.just(HookResult.continue_());
        }

        persistTurn(loopCtx, context.getSessionId());
        saveCheckpoint(loopCtx, context.getSessionId());
        return Mono.just(HookResult.continue_());
    }

    private void persistTurn(LoopContext ctx, String sessionId) {
        Msg userMsg = null;
        Msg assistantMsg = null;
        List<Msg> userMsgs = new ArrayList<>();
        List<Msg> assistantMsgs = new ArrayList<>();
        List<Msg> toolMsgs = new ArrayList<>();

        for (Msg m : ctx.getMessages()) {
            if (m.getRole() == MsgRole.USER) { userMsg = m; userMsgs.add(m); }
            if (m.getRole() == MsgRole.ASSISTANT) { assistantMsg = m; assistantMsgs.add(m); }
            if (m.getRole() == MsgRole.TOOL) toolMsgs.add(m);
        }

        ChatTurn turn = new ChatTurn(IdGenerator.nextId(),
            sessionId, ctx.getIteration(),
            userMsg != null ? userMsg.getTextContent() : "",
            assistantMsg != null ? assistantMsg.getTextContent() : null,
            null, LocalDateTime.now(),
            userMsgs, assistantMsgs, toolMsgs,
            new ArrayList<>(ctx.getMessages()));

        stateStore.addTurn(new SessionId(sessionId), turn).subscribe();
    }

    private void saveCheckpoint(LoopContext ctx, String sessionId) {
        AgentState state = new AgentState(ctx.getAgentName(), sessionId,
            ctx.getIteration(), new ArrayList<>(ctx.getMessages()),
            Map.of(), ctx.getTotalTokens(), false, null,
            System.currentTimeMillis());

        state.setPendingInterventionId(ctx.getInterventionId());
        state.setInterventionType(ctx.getInterventionType());
        state.setPausedToolArgsJson(ctx.getPausedToolArgs());

        stateStore.saveCheckpoint(state).subscribe();
    }
}
