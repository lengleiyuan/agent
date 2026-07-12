package cd.lan1akea.core.model;

import cd.lan1akea.core.message.Msg;

import java.util.List;

/**
 * Token 估算策略接口。
 * 不同模型可采用不同算法（字符比率/字节对编码等）。
 */
public interface TokenEstimator {

    /**
     * 估算消息列表的总 Token 数。
     *
     * @param messages 要估算的消息
     * @return 估算 Token 数
     */
    int estimate(List<Msg> messages);

    /**
     * 估算单条消息的 Token 数。
     *
     * @param message 要估算的消息
     * @return 估算 Token 数
     */
    int estimate(Msg message);

    /**
     * 估算原始文本的 Token 数。
     *
     * @param text 原始文本
     * @return 估算 Token 数
     */
    int estimate(String text);

    /**
     * 基于字符数/比率的估算器。
     */
    static TokenEstimator charBased(double charsPerToken) {
        return new CharBasedTokenEstimator(charsPerToken);
    }

    /**
     * 基于 cl100k_base 编码的精确估算器（对齐 OpenAI tiktoken）。
     */
    static TokenEstimator cl100k() {
        return new Cl100kTokenEstimator();
    }

    /**
     * 默认估算器：cl100k_base 精确编码。
     */
    static TokenEstimator defaults() {
        return cl100k();
    }
}
