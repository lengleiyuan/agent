package cd.lan1akea.core.model;

import cd.lan1akea.core.message.Msg;

/**
 * 结构化输出提醒器。
 * <p>
 * 在 LLM 返回不符合预期结构时，自动注入提醒消息引导模型重新生成。
 * 支持最多 3 次重试。
 * </p>
 */
public class StructuredOutputReminder {

    /** 最大重试次数 */
    private static final int MAX_RETRIES = 3;

    /**
     * 构建纠错提醒消息。
     *
     * @param expectedSchema 预期的 JSON Schema 描述
     * @param actualOutput   实际输出内容
     * @param errorMessage   解析错误信息
     * @param retryCount     当前重试次数
     * @return 提醒消息，如果超过最大重试返回 null
     */
    public static Msg buildReminder(String expectedSchema, String actualOutput,
                                     String errorMessage, int retryCount) {
        if (retryCount >= MAX_RETRIES) {
            return null;
        }

        String reminder = "你的上一轮输出不符合预期的 JSON Schema。请严格按照以下格式重新输出。\n"
            + "预期格式: " + expectedSchema + "\n"
            + "错误: " + errorMessage + "\n"
            + "这是第 " + (retryCount + 1) + " 次重试，最多 " + MAX_RETRIES + " 次。";

        return cd.lan1akea.core.message.SystemMessage.of(reminder);
    }
}
