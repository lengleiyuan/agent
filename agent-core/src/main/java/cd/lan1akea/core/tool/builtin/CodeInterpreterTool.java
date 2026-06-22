package cd.lan1akea.core.tool.builtin;

import cd.lan1akea.core.tool.ToolBase;
import cd.lan1akea.core.tool.ToolCallParam;
import cd.lan1akea.core.tool.ToolResult;
import reactor.core.publisher.Mono;

/**
 * 代码解释器工具（需配置沙箱环境）。
 */
public class CodeInterpreterTool extends ToolBase {

    public CodeInterpreterTool() {
        declareStringParam("language", "编程语言（python/javascript）", false);
        declareStringParam("code", "要执行的代码", true);
    }

    @Override
    public String getName() { return "code_interpreter"; }

    @Override
    public String getDescription() { return "在沙箱环境中执行代码，需要人工审批"; }

    @Override
    public boolean requiresApproval() { return true; }

    @Override
    public Mono<ToolResult> execute(ToolCallParam params) {
        return Mono.fromCallable(() -> {
            validateParams(params);
            String language = params.getString("language");
            if (language == null) language = "python";
            String code = params.getString("code");
            return doExecute(language, code);
        });
    }

    /** 子类覆写接入实际沙箱 */
    protected ToolResult doExecute(String language, String code) {
        return ToolResult.failure("代码解释器需要配置沙箱环境（Docker/gVisor），请覆写 doExecute() 方法");
    }
}
