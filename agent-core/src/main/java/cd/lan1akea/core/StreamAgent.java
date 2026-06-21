package cd.lan1akea.core;

import cd.lan1akea.core.message.Message;
import com.alibaba.fastjson2.JSONObject;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Name StreamAgent.java
 * Author lan1akea
 * Date 2026/06/21
 */
public interface StreamAgent extends Agent {

    /**
     * Stream execution events based on current state without adding new input.
     *
     * @param options Stream configuration options
     * @return Flux of events emitted during execution
     */
    default Flux<Event> stream(StreamOptions options) {
        return stream(List.of(), options);
    }

    /**
     * Stream execution events with structured output support based on current state.
     *
     * @param structuredModel Class defining the structure of the output
     * @return Flux of events emitted during execution
     */
    default Flux<Event> stream(Class<?> structuredModel) {
        return stream(List.of(), StreamOptions.defaults(), structuredModel);
    }

    /**
     * Stream execution events with structured output support based on current state.
     *
     * @param options Stream configuration options
     * @param structuredModel Class defining the structure of the output
     * @return Flux of events emitted during execution
     */
    default Flux<Event> stream(StreamOptions options, Class<?> structuredModel) {
        return stream(List.of(), options, structuredModel);
    }

    /**
     * Stream execution events for a single message with default options.
     *
     * @param msg Input message
     * @return Flux of events emitted during execution
     */
    default Flux<Event> stream(Message msg) {
        return stream(msg, StreamOptions.defaults());
    }

    /**
     * Stream execution events for a single message.
     *
     * @param msg Input message
     * @param options Stream configuration options
     * @return Flux of events emitted during execution
     */
    default Flux<Event> stream(Message msg, StreamOptions options) {
        return stream(List.of(msg), options);
    }

    /**
     * Stream execution events for a single message with structured output support.
     *
     * @param msg Input message
     * @param options Stream configuration options
     * @param structuredModel Class defining the structure of the output
     * @return Flux of events emitted during execution
     */
    default Flux<Event> stream(Message msg, StreamOptions options, Class<?> structuredModel) {
        return stream(List.of(msg), options, structuredModel);
    }

    /**
     * Stream execution events for a single message with JSON schema support.
     *
     * @param msg Input message
     * @param options Stream configuration options
     * @param schema JSON schema defining the structure
     * @return Flux of events emitted during execution
     */
    default Flux<Event> stream(Message msg, StreamOptions options, JSONObject schema) {
        return stream(List.of(msg), options, schema);
    }

    /**
     * Stream execution events for multiple messages with default options.
     *
     * @param msgs Input messages
     * @return Flux of events emitted during execution
     */
    default Flux<Event> stream(List<Message> msgs) {
        return stream(msgs, StreamOptions.defaults());
    }

    /**
     * Stream execution events in real-time as the agent processes the input.
     *
     * @param msgs Input messages
     * @param options Stream configuration options
     * @return Flux of events emitted during execution
     */
    Flux<Event> stream(List<Message> msgs, StreamOptions options);

    /**
     * Stream execution events with structured output support.
     *
     * @param msgs Input messages
     * @param options Stream configuration options
     * @param structuredModel Class defining the structure of the output
     * @return Flux of events emitted during execution
     */
    Flux<Event> stream(List<Message> msgs, StreamOptions options, Class<?> structuredModel);

    /**
     * Stream execution events with JSON schema support.
     *
     * @param msgs Input messages
     * @param options Stream configuration options
     * @param schema JSON schema defining the structure
     * @return Flux of events emitted during execution
     */
    Flux<Event> stream(List<Message> msgs, StreamOptions options, JSONObject schema);
}
