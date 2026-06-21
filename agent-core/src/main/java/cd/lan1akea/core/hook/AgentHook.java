package cd.lan1akea.core.hook;

import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;

/**
 * Name AgentHook.java
 * Author lan1akea
 * Date 2026/06/21
 */
public interface AgentHook {

    <T extends AgentHookEvent> Mono<T> onEvent(T event);


    default List<Object> tools() {
        return Collections.emptyList();
    }


    default int priority() {
        return 100;
    }
}
