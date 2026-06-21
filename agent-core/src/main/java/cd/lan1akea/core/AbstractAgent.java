package cd.lan1akea.core;

import cd.lan1akea.core.hook.AgentHook;
import cd.lan1akea.core.interruption.InterruptSource;
import cd.lan1akea.core.message.Message;
import com.alibaba.fastjson2.JSONObject;
import lombok.Getter;
import reactor.core.publisher.Flux;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Name AbstractAgent.java
 * Author lan1akea
 * Date 2026/06/21
 */
@Getter
public abstract class AbstractAgent implements StreamAgent {

    private final String agentId;
    private final String name;
    private final String description;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final boolean checkRunning;
    private final List<AgentHook> hooks;
    private final Map<String, List<AbstractAgent>> hubSubscribers = new ConcurrentHashMap<>();

    // Interrupt state management (available to all agents)
    private final AtomicBoolean interruptFlag = new AtomicBoolean(false);
    private final AtomicReference<Message> userInterruptMessage = new AtomicReference<>(null);
    // Hook non-null
    private static final Comparator<AgentHook> HOOK_COMPARATOR = Comparator.comparingInt(AgentHook::priority);
    private final AtomicReference<InterruptSource> interruptSource =
            new AtomicReference<>(InterruptSource.USER);

  /*  private final CopyOnWriteArrayList<RuntimeContextAware> runtimeContextAwareHooks =
            new CopyOnWriteArrayList<>();
    private final AtomicReference<RuntimeContext> currentRuntimeContext = new AtomicReference<>();*/

    public AbstractAgent(String name, String description, boolean checkRunning, List<AgentHook> hooks) {
        this.agentId = UUID.randomUUID().toString();
        this.name = name;
        this.description = description;
        this.checkRunning = checkRunning;
        this.hooks = new CopyOnWriteArrayList<>(hooks != null ? hooks : List.of());
       /* sortHooks();
        for (Hook h : this.hooks) {
            registerRuntimeContextHookIfNeeded(h);
        }*/
    }

    @Override
    public Flux<Event> stream(List<Message> msgs, StreamOptions options) {
        return null;
    }

    @Override
    public Flux<Event> stream(List<Message> msgs, StreamOptions options, Class<?> structuredModel) {
        return null;
    }

    @Override
    public Flux<Event> stream(List<Message> msgs, StreamOptions options, JSONObject schema) {
        return null;
    }

}
