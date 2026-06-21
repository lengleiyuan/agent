/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cd.lan1akea.core.hook;


import cd.lan1akea.core.Agent;
import cd.lan1akea.core.message.ContentChunk;
import cd.lan1akea.core.message.Message;
import cd.lan1akea.core.message.MessageRole;
import cd.lan1akea.core.message.TextChunk;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;



@Getter
public abstract class AgentHookEvent {

    private final HookEventType type;
    private final Agent agent;
    private final long timestamp;

    private Message systemMsg;


    protected AgentHookEvent(HookEventType type, Agent agent) {
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.agent = Objects.requireNonNull(agent, "agent cannot be null");
        this.timestamp = System.currentTimeMillis();
    }




    public final Message getSystemMessage() {
        return systemMsg;
    }

    public final void setSystemMessage(Message systemMsg) {
        this.systemMsg = systemMsg;
    }


    public final void appendSystemContent(String text) {
        Objects.requireNonNull(text, "text cannot be null");
        appendSystemContent(TextChunk.builder().text(text).build());
    }


    public final void appendSystemContent(ContentChunk chuck) {
        Objects.requireNonNull(chuck, "block cannot be null");
        if (systemMsg == null) {
            systemMsg = Message.builder().name("system").role(MessageRole.SYSTEM).content(chuck).build();
        } else {
            List<ContentChunk> merged = new ArrayList<>(systemMsg.getContent());
            merged.add(chuck);
            systemMsg =
                    Message.builder()
                            .id(systemMsg.getId())
                            .name(systemMsg.getName())
                            .role(MessageRole.SYSTEM)
                            .content(merged)
                            .metadata(systemMsg.getMetadata())
                            .timestamp(systemMsg.getTimestamp())
                            .build();
        }
    }
}
