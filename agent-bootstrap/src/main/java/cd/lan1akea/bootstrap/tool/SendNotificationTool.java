package cd.lan1akea.bootstrap.tool;

import cd.lan1akea.core.exception.HumanInterventionException;
import cd.lan1akea.core.tool.*;
import reactor.core.publisher.Mono;

/**
 * 通知发送工具（演示 CLARIFY 流程）。
 * LLM 起草通知内容，人工可修改后再发送。
 */
public class SendNotificationTool extends ToolBase {

    public SendNotificationTool() {
        declareStringParam("channel", "通知渠道（email/sms/push）", true);
        declareStringParam("message", "通知内容", true);
    }

    @Override
    public String getName() { return "send_notification"; }

    @Override
    public String getDescription() { return "发送通知。LLM 起草内容后需人工审核，可修改内容后发送"; }

    @Override
    public String getRiskLevel() { return "MEDIUM"; }

    @Override
    public Mono<ToolResult> execute(ToolCallContext params) {
        validateParams(params);
        String channel = params.getString("channel");
        String message = params.getString("message");

        if (!params.isApproved()) {
            throw HumanInterventionException.clarify("send_notification",
                "请审核通知内容 — 渠道: " + channel,
                params);
        }

        return Mono.just(ToolResult.success(
            "通知已发送 [" + channel + "]: " + message + " [模拟]"));
    }
}
