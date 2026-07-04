package cd.lan1akea.core.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JsonUtilsTest {

    @Test
    void trailingComma_shouldBeRemoved() {
        String input = "{\"key\":\"value\",}";
        String result = JsonUtils.repairJson(input);
        assertEquals("{\"key\":\"value\"}", result);
        assertTrue(JsonUtils.isValidJson(result), "result should be valid JSON: " + result);
    }

    @Test
    void unquotedKey_shouldBeQuoted() {
        String result = JsonUtils.repairJson("{approval_id: \"123\"}");
        assertTrue(JsonUtils.isValidJson(result), "result should be valid JSON: " + result);
        assertTrue(result.contains("\"approval_id\""));
    }

    @Test
    void missingColon_shouldBeFixed() {
        String result = JsonUtils.repairJson("{\"comment\"\"hello\"}");
        assertTrue(JsonUtils.isValidJson(result), "result should be valid JSON: " + result);
        assertTrue(result.contains("\"comment\":"));
    }

    @Test
    void unclosedStringValue_shouldBeClosed() {
        String result = JsonUtils.repairJson("{\"key\": \"value}");
        assertTrue(JsonUtils.isValidJson(result), "result should be valid JSON: " + result);
    }

    @Test
    void codeFenceWrapped_shouldBeStripped() {
        String result = JsonUtils.repairJson("```json\n{\"key\": \"value\"}\n```");
        assertTrue(JsonUtils.isValidJson(result), "result should be valid JSON: " + result);
        assertEquals("{\"key\": \"value\"}", result);
    }

    @Test
    void trailingTextAfterBrace_shouldBeTrimmed() {
        String result = JsonUtils.repairJson("{\"key\": \"value\"} some extra text");
        assertTrue(JsonUtils.isValidJson(result), "result should be valid JSON: " + result);
        assertEquals("{\"key\": \"value\"}", result);
    }

    @Test
    void alreadyValidJson_shouldReturnUnchanged() {
        String input = "{\"approval_id\": \"123\", \"action\": \"approve\"}";
        String result = JsonUtils.repairJson(input);
        assertEquals(input, result);
    }

    @Test
    void nullOrEmpty_shouldReturnUnchanged() {
        assertEquals("", JsonUtils.repairJson(""));
    }
}
