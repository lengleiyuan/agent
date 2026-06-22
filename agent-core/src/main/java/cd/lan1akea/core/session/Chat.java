package cd.lan1akea.core.session;

import cd.lan1akea.core.message.Msg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单次聊天。
 * <p>
 * 一次完整的用户输入 → Agent 输出流程。
 * </p>
 */
public class Chat {

    private final SessionId sessionId;
    private final List<ChatTurn> turns;

    public Chat(SessionId sessionId) {
        this.sessionId = sessionId;
        this.turns = new ArrayList<>();
    }

    public void addTurn(ChatTurn turn) {
        turns.add(turn);
    }

    public SessionId getSessionId() { return sessionId; }
    public List<ChatTurn> getTurns() { return Collections.unmodifiableList(turns); }
    public int getTurnCount() { return turns.size(); }
}
