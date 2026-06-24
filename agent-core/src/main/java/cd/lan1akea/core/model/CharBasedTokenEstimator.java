package cd.lan1akea.core.model;

import cd.lan1akea.core.message.*;

import java.util.List;

/**
 * 基于字符数比率的 Token 估算器，覆盖所有 ContentBlock 类型。
 */
class CharBasedTokenEstimator implements TokenEstimator {

    private final double charsPerToken;

    CharBasedTokenEstimator(double charsPerToken) {
        this.charsPerToken = charsPerToken;
    }

    @Override
    public int estimate(List<Msg> messages) {
        int total = 0;
        for (Msg msg : messages) {
            total += countChars(msg);
        }
        return (int) (total / charsPerToken);
    }

    @Override
    public int estimate(Msg message) {
        return (int) (countChars(message) / charsPerToken);
    }

    private int countChars(Msg msg) {
        int chars = 0;
        for (ContentBlock block : msg.getContentBlocks()) {
            if (block instanceof TextBlock tb) {
                chars += tb.getText().length();
            } else if (block instanceof ToolUseBlock tb) {
                chars += tb.getName().length() + tb.getArguments().length();
            } else if (block instanceof ToolResultBlock tr) {
                chars += tr.getContent().length();
            } else if (block instanceof ThinkingBlock th) {
                chars += th.getThinking().length();
            }
        }
        return chars;
    }
}
