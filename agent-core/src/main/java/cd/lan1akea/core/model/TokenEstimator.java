package cd.lan1akea.core.model;

import cd.lan1akea.core.message.Msg;

import java.util.List;

/**
 * Token 估算策略接口。
 * 不同模型可采用不同算法（字符比率/字节对编码等）。
 */
public interface TokenEstimator {

    int estimate(List<Msg> messages);

    int estimate(Msg message);

    /** 基于字符数/比率的估算器 */
    static TokenEstimator charBased(double charsPerToken) {
        return new CharBasedTokenEstimator(charsPerToken);
    }

    /** 默认估算器：char/2.0 */
    static TokenEstimator defaults() {
        return charBased(2.0);
    }
}
