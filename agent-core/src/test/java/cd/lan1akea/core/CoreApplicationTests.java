package cd.lan1akea.core;

import cd.lan1akea.core.message.*;
import cd.lan1akea.core.util.JsonUtils;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * agent-core 基础单元测试。
 */
class CoreApplicationTests {

    @Test
    void testMsgBuilder() {
        Msg msg = Msg.builder(MsgRole.USER)
            .addText("你好")
            .build();
        assertEquals(MsgRole.USER, msg.getRole());
        assertEquals("你好", msg.getTextContent());
        assertNotNull(msg.getId());
    }

    @Test
    void testSystemMessage() {
        SystemMessage msg = SystemMessage.of("你是一个助手");
        assertEquals(MsgRole.SYSTEM, msg.getRole());
        assertEquals("你是一个助手", msg.getTextContent());
    }

    @Test
    void testUserMessage() {
        UserMessage msg = UserMessage.of("帮我搜索");
        assertEquals(MsgRole.USER, msg.getRole());
    }

    @Test
    void testAssistantMessage() {
        AssistantMessage msg = AssistantMessage.of("好的");
        assertEquals(MsgRole.ASSISTANT, msg.getRole());
    }

    @Test
    void testJsonUtils() {
        String json = JsonUtils.toJson(new TestPojo("hello", 42));
        assertNotNull(json);
        assertTrue(json.contains("hello"));
    }

    @Test
    void testToolUseBlock() {
        ToolUseBlock block = new ToolUseBlock("call_1", "calculator", "{\"expression\":\"2+3\"}");
        assertEquals("calculator", block.getName());
        assertEquals("call_1", block.getId());
    }

    @Test
    void testToolResultBlock() {
        ToolResultBlock block = ToolResultBlock.success("call_1", "5");
        assertEquals("call_1", block.getToolUseId());
        assertFalse(block.isError());
    }

    @Test
    void testMsgWithToolCalls() {
        Msg msg = AssistantMessage.builder()
            .addText("我来计算")
            .addToolUse("call_1", "calculator", "{\"expression\":\"2+3\"}")
            .build();
        assertTrue(msg.hasToolCalls());
        assertEquals(1, msg.getToolUseBlocks().size());
    }

    /** 测试POJO */
    public static class TestPojo {
        private String name;
        private int value;

        public TestPojo() {}

        public TestPojo(String name, int value) {
            this.name = name;
            this.value = value;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }
    }
}
