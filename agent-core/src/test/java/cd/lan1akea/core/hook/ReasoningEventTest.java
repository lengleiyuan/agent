package cd.lan1akea.core.hook;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.MsgRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReasoningEventTest {

    @Test
    void testBypassMessageNullByDefault() {
        ReasoningEvent e = new ReasoningEvent(HookEventType.PRE_REASONING);
        assertNull(e.getBypassMessage());
    }

    @Test
    void testSetAndGetBypassMessage() {
        ReasoningEvent e = new ReasoningEvent(HookEventType.PRE_REASONING);
        Msg msg = Msg.builder(MsgRole.ASSISTANT).addText("bypass answer").build();
        e.setBypassMessage(msg);
        assertNotNull(e.getBypassMessage());
        assertEquals("bypass answer", e.getBypassMessage().getTextContent());
        assertEquals(MsgRole.ASSISTANT, e.getBypassMessage().getRole());
    }

    @Test
    void testSetBypassMessageDoesNotAffectMessages() {
        ReasoningEvent e = new ReasoningEvent(HookEventType.PRE_REASONING);
        Msg userMsg = Msg.builder(MsgRole.USER).addText("hello").build();
        e.setMessages(List.of(userMsg));
        e.setBypassMessage(Msg.builder(MsgRole.ASSISTANT).addText("answer").build());

        assertEquals(1, e.getMessages().size());
        assertEquals("hello", e.getMessages().get(0).getTextContent());
        assertEquals("answer", e.getBypassMessage().getTextContent());
    }
}
