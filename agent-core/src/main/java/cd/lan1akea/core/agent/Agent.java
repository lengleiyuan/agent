package cd.lan1akea.core.agent;

import cd.lan1akea.core.message.Msg;

/**
 * Agent 顶层接口。
 * 定义所有 Agent 共有的最小能力：标识、中断。
 * 对话能力按职责拆分到子接口：
 */
public interface Agent {

    /**
     * Agent 唯一名称
     * */
    String getName();

    /**
     * Agent 唯一标识
     * */
    String getId();

    /**
     * 中断当前正在执行的对话。
     * 协作式中断：设置中断标志后，ReActLoop 在当前迭代结束后停止。
     * 外部线程安全，可在任何线程调用。
     */
    void interrupt();

    /**
     * 中断并注入反馈消息。
     */
    void interrupt(Msg feedbackMsg);
}
