package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.GenerateOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * ReAct 循环上下文。
 * <p>
 * 携带循环执行所需的全部状态：消息历史、当前迭代、生成选项等。
 * </p>
 */
public class LoopContext {

    private final String agentName;
    private final List<Msg> messages;
    private final GenerateOptions generateOptions;
    private final int maxIterations;
    private final boolean stream;
    private int iteration;
    private ChatResponse lastResponse;

    private LoopContext(Builder builder) {
        this.agentName = builder.agentName;
        this.messages = new ArrayList<>(builder.messages);
        this.generateOptions = builder.generateOptions;
        this.maxIterations = builder.maxIterations;
        this.stream = builder.stream;
        this.iteration = 0;
    }

    public void addMessage(Msg msg) {
        messages.add(msg);
    }

    public void addMessages(List<Msg> msgs) {
        messages.addAll(msgs);
    }

    public String getAgentName() { return agentName; }
    public List<Msg> getMessages() { return messages; }
    public GenerateOptions getGenerateOptions() { return generateOptions; }
    public int getMaxIterations() { return maxIterations; }
    public boolean isStream() { return stream; }
    public int getIteration() { return iteration; }
    public void setIteration(int iteration) { this.iteration = iteration; }
    public ChatResponse getLastResponse() { return lastResponse; }
    public void setLastResponse(ChatResponse lastResponse) { this.lastResponse = lastResponse; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String agentName;
        private List<Msg> messages;
        private GenerateOptions generateOptions;
        private int maxIterations = 10;
        private boolean stream;

        public Builder agentName(String agentName) { this.agentName = agentName; return this; }
        public Builder messages(List<Msg> messages) { this.messages = messages; return this; }
        public Builder generateOptions(GenerateOptions generateOptions) { this.generateOptions = generateOptions; return this; }
        public Builder maxIterations(int maxIterations) { this.maxIterations = maxIterations; return this; }
        public Builder stream(boolean stream) { this.stream = stream; return this; }

        public LoopContext build() { return new LoopContext(this); }
    }
}
