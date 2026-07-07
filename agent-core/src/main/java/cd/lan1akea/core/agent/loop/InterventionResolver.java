package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.EventPayload;
import cd.lan1akea.core.CoreConstants.FinishReason;
import cd.lan1akea.core.CoreConstants.Intervention;
import cd.lan1akea.core.exception.HumanInterventionException;
import cd.lan1akea.core.intervention.InterventionRequest;
import cd.lan1akea.core.intervention.InterventionStore;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.MsgRole;
import cd.lan1akea.core.message.ToolUseBlock;
import cd.lan1akea.core.model.ChatStreamChunk;
import cd.lan1akea.core.tool.ToolCallContext;
import cd.lan1akea.core.tool.ToolResult;
import cd.lan1akea.core.util.JsonUtils;

import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人工介入恢复处理器。
 * <p>将介入请求的创建和恢复逻辑从 LoopExecutor 提取到独立组件。
 * 通过 ResolvedIntervention 实现决策-执行分离，
 * 避免直接返回 Flux 而依赖 LoopExecutor 的私有状态机入口。
 */
public class InterventionResolver {

    /** 介入请求存储器 */
    private final InterventionStore interventionStore;
    /** 工具调用编排器，用于介入恢复时的工具重执行 */
    private final ToolCallOrchestrator toolOrchestrator;

    /**
     * 介入恢复决策结果。
     * <p>将 resumeFromIntervention 的 6 条分支归为 3 种 Action。
     */
    public static class ResolvedIntervention {

        /** 恢复动作类型 */
        public enum Action {
            /** 清除介入状态，重新进入主循环 */
            RE_ENTER,
            /** 返回介入等待 chunk，终止当前流 */
            RETURN_CHUNK,
            /** 执行工具（或失败结果），continue 到 Observe */
            EXECUTE_AND_CONTINUE
        }

        private final Action action;
        private final ChatStreamChunk chunk;
        private final String callId;
        private final Mono<ToolResult> execution;

        private ResolvedIntervention(Action action, ChatStreamChunk chunk,
                                      String callId, Mono<ToolResult> execution) {
            this.action = action;
            this.chunk = chunk;
            this.callId = callId;
            this.execution = execution;
        }

        /** 清除介入状态，重新进入主循环 */
        public static ResolvedIntervention reEnter() {
            return new ResolvedIntervention(Action.RE_ENTER, null, null, null);
        }

        /** 返回介入等待 chunk，终止当前流 */
        public static ResolvedIntervention returnChunk(ChatStreamChunk chunk) {
            return new ResolvedIntervention(Action.RETURN_CHUNK, chunk, null, null);
        }

        /** 执行工具并继续到 Observe 阶段 */
        public static ResolvedIntervention executeAndContinue(String callId,
                                                               Mono<ToolResult> execution) {
            return new ResolvedIntervention(Action.EXECUTE_AND_CONTINUE, null, callId, execution);
        }

        /** @return 恢复动作类型 */
        public Action getAction() { return action; }
        /** @return 介入等待 chunk（RETURN_CHUNK 时有效） */
        public ChatStreamChunk getChunk() { return chunk; }
        /** @return 工具调用 callId（EXECUTE_AND_CONTINUE 时有效） */
        public String getCallId() { return callId; }
        /** @return 工具执行 Mono（EXECUTE_AND_CONTINUE 时有效） */
        public Mono<ToolResult> getExecution() { return execution; }
    }

    /**
     * 构造介入恢复处理器。
     *
     * @param interventionStore 介入请求存储器
     * @param toolOrchestrator  工具调用编排器
     */
    public InterventionResolver(InterventionStore interventionStore,
                                 ToolCallOrchestrator toolOrchestrator) {
        this.interventionStore = interventionStore;
        this.toolOrchestrator = toolOrchestrator;
    }

    /**
     * 从人工介入状态恢复执行。
     * <p>查询介入请求状态并决定恢复策略。callId 为 null 时防御性清除后 RE_ENTER。
     *
     * @param ctx 循环上下文
     * @return 恢复决策
     */
public ResolvedIntervention resolveForRecovery(LoopContext ctx) {
        String id = ctx.getInterventionState().getInterventionId();
        if (id == null) return ResolvedIntervention.reEnter();

        InterventionRequest req = interventionStore.getById(id);
        if (req == null) {
            ctx.getInterventionState().clear();
            return ResolvedIntervention.reEnter();
        }

        String callId = findToolCallId(ctx, req.getToolName());
        if (callId == null) {
            ctx.getInterventionState().clear();
            return ResolvedIntervention.reEnter();
        }

        switch (req.getStatus()) {
            case PENDING:
                return ResolvedIntervention.returnChunk(buildPendingChunk(req));
            case APPROVED:
                return ResolvedIntervention.executeAndContinue(callId,
                        executeResumeTool(ctx, req.getToolName(), resolveArgs(req, ctx), callId));
            case CLARIFIED:
                return ResolvedIntervention.executeAndContinue(callId,
                        executeResumeTool(ctx, req.getToolName(),
                                req.getModifiedArgs() != null ? req.getModifiedArgs() : Map.of(),
                                callId));
            case DENIED:
                return ResolvedIntervention.executeAndContinue(callId,
                        Mono.just(ToolResult.failure(callId, buildDeniedMessage(req))));
            case EXPIRED:
                return ResolvedIntervention.executeAndContinue(callId,
                        Mono.just(ToolResult.failure(callId, buildExpiredMessage(req))));
            default:
                return ResolvedIntervention.returnChunk(
                        buildSignalChunk(id, Intervention.MSG_WAITING,
                                req.getType().name(), req.getToolName()));
        }
    }

    /**
     * 从 HumanInterventionException 创建介入请求并中断循环。
     * <p>副作用：持久化请求、设置 ctx 介入状态、调用 ctx.interrupt()。
     */
    public ChatStreamChunk createIntervention(HumanInterventionException e, LoopContext ctx) {
        InterventionRequest req = buildRequest(e, ctx);
        String id = interventionStore.create(req);

        ctx.getInterventionState().setInterventionId(id);
        ctx.getInterventionState().setInterventionType(e.getType().name());
        if (e.getCallParam() != null) {
            ctx.getInterventionState().setPausedToolArgs(
                    JsonUtils.toCompactJson(e.getCallParam().getArgumentsMap()));
        }
        ctx.interrupt();

        return buildSignalChunk(id, e.getReason(), e.getType().name(), e.getToolName());
    }

    /**
     * 按工具名查找最后一条 assistant 消息中对应 tool_use 的 callId。
     * 无匹配时回退到最后一个 tool_use 的 callId。
     */
    public String findToolCallId(LoopContext ctx, String toolName) {
        int idx = lastAssistantIndex(ctx);
        if (idx < 0) return null;
        List<ToolUseBlock> tools = ctx.getMessages().get(idx).getToolUseBlocks();
        if (tools == null || tools.isEmpty()) return null;
        for (int i = tools.size() - 1; i >= 0; i--) {
            if (tools.get(i).getName().equals(toolName)) return tools.get(i).getId();
        }
        return tools.get(tools.size() - 1).getId();
    }

    /**
     * 构建介入信号 chunk，通知前端需要人工介入。
     */
    public ChatStreamChunk buildSignalChunk(String id, String question,
                                             String interventionType, String toolName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(EventPayload.TYPE, Intervention.PAYLOAD_TYPE);
        payload.put(EventPayload.INTERVENTION_ID, id);
        payload.put(EventPayload.QUESTION, question);
        payload.put(EventPayload.INTERVENTION_TYPE, interventionType);
        payload.put(EventPayload.TOOL_NAME, toolName);
        return ChatStreamChunk.builder()
                .delta(JsonUtils.toCompactJson(payload))
                .type(Intervention.CHUNK_TYPE)
                .finishReason(Intervention.FINISH_REASON)
                .build();
    }

    // ============================================================
    // private helpers
    // ============================================================

    private int lastAssistantIndex(LoopContext ctx) {
        List<Msg> msgs = ctx.getMessages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).getRole() == MsgRole.ASSISTANT) return i;
        }
        return -1;
    }

    private InterventionRequest buildRequest(HumanInterventionException e, LoopContext ctx) {
        return InterventionRequest.builder()
                .type(toInterventionType(e.getType()))
                .sessionId(ctx.getSessionId())
                .requestId(ctx.getRequestId())
                .tenantId(ctx.getTenantId())
                .agentName(ctx.getAgentName())
                .toolName(e.getToolName())
                .question(e.getReason())
                .toolArgs(e.getCallParam() != null ? e.getCallParam().getArgumentsMap() : null)
                .recentMessages(truncateMessages(ctx.getMessages()))
                .ttlMinutes(e.getTtlMinutes())
                .build();
    }

    private List<Msg> truncateMessages(List<Msg> messages) {
        int size = messages.size();
        int from = Math.max(0, size - Intervention.RECENT_MSG_LIMIT);
        return new ArrayList<>(messages.subList(from, size));
    }

    private Map<String, Object> resolveArgs(InterventionRequest req, LoopContext ctx) {
        if (req.getToolArgs() != null && !req.getToolArgs().isEmpty()) return req.getToolArgs();
        if (ctx.getInterventionState().getPausedToolArgs() != null)
            return JsonUtils.safeParseMap(ctx.getInterventionState().getPausedToolArgs());
        return Map.of();
    }

    private ChatStreamChunk buildPendingChunk(InterventionRequest req) {
        return ChatStreamChunk.of(
                "[等待处理] " + req.getType() + " — " + req.getToolName() + ": "
                        + (req.getQuestion() != null ? req.getQuestion() : ""),
                FinishReason.INTERRUPTED);
    }

    private String buildDeniedMessage(InterventionRequest req) {
        return Intervention.MSG_DENIED + " — " + req.getToolName()
                + (req.getResolution() != null && !req.getResolution().isBlank()
                        ? ": " + req.getResolution() : "");
    }

    private String buildExpiredMessage(InterventionRequest req) {
        return Intervention.MSG_EXPIRED + " — " + req.getToolName();
    }

    private Mono<ToolResult> executeResumeTool(LoopContext ctx, String toolName,
                                                Map<String, Object> args, String callId) {
        ToolCallContext param = ToolCallContext.builder()
                .callId(callId)
                .toolName(toolName)
                .arguments(args != null ? args : Map.of())
                .tenantId(ctx.getTenantId())
                .userId(ctx.getUserId())
                .sessionId(ctx.getSessionId())
                .attributes(ctx.getAttributes())
                .build();
        param.setApproved(true);
        return toolOrchestrator.executeDirect(param, ctx);
    }

    private static InterventionRequest.Type toInterventionType(HumanInterventionException.Type t) {
        switch (t) {
            case TOOL_APPROVAL: return InterventionRequest.Type.TOOL_APPROVAL;
            case TOOL_CLARIFY: return InterventionRequest.Type.TOOL_CLARIFY;
            default: return InterventionRequest.Type.TOOL_APPROVAL;
        }
    }
}
