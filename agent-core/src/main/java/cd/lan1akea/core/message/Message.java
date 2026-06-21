package cd.lan1akea.core.message;

import cd.lan1akea.core.utils.TypeUtils;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Name Message.java
 * Author lan1akea
 * Date 2026/06/21
 */
@Data
public class Message {


    private final String id;

    private final String name;

    private final MessageRole role;

    private final List<ContentChunk> content;

    private final Map<String, Object> metadata;

    private final String timestamp;

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());


    private Message(String id, String name, MessageRole role,
                    List<ContentChunk> content, Map<String, Object> metadata,
                    String timestamp) {
        this.id = id;
        this.name = name;
        this.role = role;
        this.content =
                Objects.nonNull(content)
                        ? content.stream().filter(Objects::nonNull).toList()
                        : List.of();
        this.metadata = new HashMap<>();
        if (Objects.nonNull(metadata)) {
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (Objects.isNull(key) || Objects.isNull(value)) {
                    continue;
                }
                this.metadata.put(key, value);
            }
        }
        this.timestamp = timestamp;
    }

    public static Builder builder() {
        return new Builder();
    }

    @JSONField(serialize = false, deserialize = false)
    public <T extends ContentChunk> boolean hasContentChucks(Class<T> chuckClass) {
        return content.stream().anyMatch(chuckClass::isInstance);
    }

    @JSONField(serialize = false, deserialize = false)
    public <T extends ContentChunk> List<T> getContentChucks(Class<T> chuckClass) {
        return content.stream()
                .filter(chuckClass::isInstance)
                .map(b -> TypeUtils.safeCast(b, chuckClass))
                .collect(Collectors.toList());
    }

    @JSONField(serialize = false, deserialize = false)
    public ContentChunk getFirstContentchuck() {
        return content.isEmpty() ? null : content.get(0);
    }

    @JSONField(serialize = false, deserialize = false)
    public <T extends ContentChunk> T getFirstContentChuck(Class<T> chuckClass) {
        return content.stream()
                .filter(chuckClass::isInstance)
                .map(b -> TypeUtils.safeCast(b, chuckClass))
                .findFirst()
                .orElse(null);
    }


    public boolean hasStructuredData() {
        return metadata != null && metadata.containsKey(MessageConstant.STRUCTURED_OUTPUT);
    }


    public <T> T getStructuredData(Class<T> targetClass) {
        if (metadata == null || metadata.isEmpty()) {
            throw new IllegalStateException(
                    "No structured data in message. Use hasStructuredData() to check first.");
        }
        Object structuredOutput = metadata.get(MessageConstant.STRUCTURED_OUTPUT);
        if (structuredOutput == null) {
            throw new IllegalStateException(
                    "No structured output in message metadata. Key '"
                            + MessageConstant.STRUCTURED_OUTPUT
                            + "' not found.");
        }
        try {
            return JSONObject.from(structuredOutput).toJavaObject(targetClass);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to convert metadata to "
                            + targetClass.getSimpleName()
                            + ". Ensure the target class has appropriate fields matching metadata"
                            + " keys.",
                    e);
        }
    }

    public static class Builder {

        private String id;

        private String name;

        private MessageRole role = MessageRole.USER;

        private List<ContentChunk> content = List.of();

        private Map<String, Object> metadata = Map.of();

        private String timestamp = TIMESTAMP_FORMATTER.format(Instant.now());

        /**
         * Creates a new builder with a randomly generated message ID.
         */
        public Builder() {
            randomId();
        }

        /**
         * Sets the unique identifier for the message.
         *
         * @param id The message ID
         * @return This builder for chaining
         */
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /**
         * Generates a random UUID for the message ID.
         */
        private void randomId() {
            this.id = UUID.randomUUID().toString();
        }

        /**
         * Sets the optional name for the message.
         *
         * @param name The message name
         * @return This builder for chaining
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the role for the message.
         *
         * @param role The message role (user, assistant, system, or tool)
         * @return This builder for chaining
         */
        public Builder role(MessageRole role) {
            this.role = role;
            return this;
        }

        /**
         * Set content from a list of content blocks.
         * @param content List of content blocks
         * @return This builder
         */
        public Builder content(List<ContentChunk> content) {
            this.content = content;
            return this;
        }

        /**
         * Set content from a single content block (convenience method).
         * The block will be wrapped in a list automatically.
         * @param block Single content block
         * @return This builder
         */
        public Builder content(ContentChunk block) {
            this.content = block == null ? List.of() : List.of(block);
            return this;
        }

        /**
         * Set content from varargs content blocks (convenience method).
         * @param blocks Content blocks
         * @return This builder
         */
        public Builder content(ContentChunk... blocks) {
            this.content = blocks == null ? List.of() : List.of(blocks);
            return this;
        }

        /**
         * Set text content from a string.
         * @param text Text content
         * @return This builder
         */
        public Builder textContent(String text) {
            this.content = List.of(TextChunk.builder().text(text).build());
            return this;
        }

        /**
         * Set metadata for structured output.
         * @param metadata Metadata map
         * @return This builder
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata == null ? Map.of() : metadata;
            return this;
        }

        /**
         * Sets the timestamp for the message.
         *
         * @param timestamp The timestamp string
         * @return This builder for chaining
         */
        public Builder timestamp(String timestamp) {
            this.timestamp =
                    timestamp == null ? TIMESTAMP_FORMATTER.format(Instant.now()) : timestamp;
            return this;
        }

        /**
         * Sets the generate reason for this message.
         *
         * <p>The generate reason indicates why this message was generated by the agent,
         * helping users understand the execution context and required follow-up actions.
         *
         * @param reason The generate reason
         * @return This builder for chaining
         */
        public Builder generateReason(GenerateReason reason) {
            if (reason != null) {
                if (this.metadata == null || this.metadata.isEmpty()) {
                    this.metadata = new HashMap<>();
                } else if (!(this.metadata instanceof HashMap)) {
                    this.metadata = new HashMap<>(this.metadata);
                }
                this.metadata.put(MessageConstant.AGENT_GENERATE_REASON, reason.name());
            }
            return this;
        }

        /**
         * Builds a new message instance with the configured properties.
         * If timestamp is not set, it will be auto-generated.
         *
         * @return A new immutable message
         */
        public Message build() {
            return new Message(id, name, role, content, metadata, timestamp);
        }
    }
}
