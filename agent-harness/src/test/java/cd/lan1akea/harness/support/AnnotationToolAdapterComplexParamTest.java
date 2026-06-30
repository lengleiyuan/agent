package cd.lan1akea.harness.support;

import cd.lan1akea.core.tool.Tool;
import cd.lan1akea.core.tool.ToolCallContext;
import cd.lan1akea.core.tool.ToolRegistry;
import cd.lan1akea.core.tool.ToolResult;
import cd.lan1akea.harness.annotation.ToolFunction;
import cd.lan1akea.harness.annotation.ToolParam;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 @ToolFunction 对复杂类型参数的支持：嵌套 POJO、List、数组等。
 */
class AnnotationToolAdapterComplexParamTest {

    // ========================================================================
    // 测试用 POJO
    // ========================================================================

    public static class NestedFilter {
        public String type;
        public int priority;
    }

    public static class SearchReq {
        public String keyword;
        public int limit;
        public boolean asc;
        public NestedFilter filter;
        public List<String> tags;
    }

    // ========================================================================
    // 工具类
    // ========================================================================

    static class ComplexSearchTool {
        @ToolFunction(name = "complex_search", description = "复杂搜索")
        public ToolResult search(@ToolParam(name = "query", description = "查询条件") SearchReq query) {
            assertNotNull(query);
            return ToolResult.success(
                "keyword=" + query.keyword + ", limit=" + query.limit
                + ", filter=" + (query.filter != null ? query.filter.type : "null"));
        }
    }

    @Test
    void complexParamSchemaShouldBeNestedObject() {
        ToolRegistry registry = new ToolRegistry();
        registry.addAdapter(new AnnotationToolAdapter());
        List<Tool> tools = registry.registerTool(new ComplexSearchTool());

        assertEquals(1, tools.size());
        Tool tool = tools.get(0);
        Map<String, Object> schema = tool.getParameters().getParametersSchema();

        // 顶层结构
        assertEquals("object", schema.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertNotNull(props);

        // query 参数的 schema 应该是嵌套 object
        @SuppressWarnings("unchecked")
        Map<String, Object> queryProp = (Map<String, Object>) props.get("query");
        assertNotNull(queryProp);
        assertEquals("object", queryProp.get("type"));
        assertNotNull(queryProp.get("properties"));

        // query.properties 应包含 keyword, limit, asc, filter, tags
        @SuppressWarnings("unchecked")
        Map<String, Object> queryProps = (Map<String, Object>) queryProp.get("properties");
        assertNotNull(queryProps.get("keyword"));
        assertNotNull(queryProps.get("limit"));
        assertNotNull(queryProps.get("filter"));
        assertNotNull(queryProps.get("tags"));
    }

    @Test
    void complexParamSchemaShouldHandleNestedFilter() {
        ToolRegistry registry = new ToolRegistry();
        registry.addAdapter(new AnnotationToolAdapter());
        List<Tool> tools = registry.registerTool(new ComplexSearchTool());
        Tool tool = tools.get(0);

        Map<String, Object> schema = tool.getParameters().getParametersSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> queryProp = (Map<String, Object>) props.get("query");
        @SuppressWarnings("unchecked")
        Map<String, Object> queryProps = (Map<String, Object>) queryProp.get("properties");

        // filter 应该是嵌套 object
        @SuppressWarnings("unchecked")
        Map<String, Object> filterProp = (Map<String, Object>) queryProps.get("filter");
        assertEquals("object", filterProp.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> filterProps = (Map<String, Object>) filterProp.get("properties");
        assertNotNull(filterProps.get("type"));
        assertNotNull(filterProps.get("priority"));
    }

    @Test
    void complexParamSchemaShouldHandleList() {
        ToolRegistry registry = new ToolRegistry();
        registry.addAdapter(new AnnotationToolAdapter());
        List<Tool> tools = registry.registerTool(new ComplexSearchTool());
        Tool tool = tools.get(0);

        Map<String, Object> schema = tool.getParameters().getParametersSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> queryProp = (Map<String, Object>) props.get("query");
        @SuppressWarnings("unchecked")
        Map<String, Object> queryProps = (Map<String, Object>) queryProp.get("properties");

        // tags 应该是 array
        @SuppressWarnings("unchecked")
        Map<String, Object> tagsProp = (Map<String, Object>) queryProps.get("tags");
        assertEquals("array", tagsProp.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> tagsItems = (Map<String, Object>) tagsProp.get("items");
        assertEquals("string", tagsItems.get("type"));
    }

    // ========================================================================
    // 执行测试：验证 raw Map 能正确转换为 POJO
    // ========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void complexParamExecutionShouldConvertRawMapToPojo() {
        ToolRegistry registry = new ToolRegistry();
        registry.addAdapter(new AnnotationToolAdapter());
        List<Tool> tools = registry.registerTool(new ComplexSearchTool());
        Tool tool = tools.get(0);

        // 模拟 LLM 传来的嵌套 JSON 被解析为 Map
        Map<String, Object> nestedFilter = Map.of("type", "text", "priority", 1);
        Map<String, Object> queryMap = Map.of(
            "keyword", "AI",
            "limit", 10,
            "asc", true,
            "filter", nestedFilter,
            "tags", List.of("java", "ai")
        );
        Map<String, Object> args = Map.of("query", queryMap);

        ToolCallContext ctx = ToolCallContext.builder()
            .callId("cx").toolName("complex_search")
            .arguments(args)
            .tenantId("tx").userId("ux")
            .build();

        ToolResult result = tool.execute(ctx).block();
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("keyword=AI"));
        assertTrue(result.getContent().contains("limit=10"));
        assertTrue(result.getContent().contains("filter=text"));
    }

    // ========================================================================
    // List 直接作为顶层参数
    // ========================================================================

    public static class Item {
        public String name;
        public int qty;
    }

    static class BatchTool {
        @ToolFunction(name = "batch_create")
        public ToolResult batch(@ToolParam(name = "items") List<Item> items) {
            assertNotNull(items);
            return ToolResult.success("count=" + items.size() + ", first=" + items.get(0).name);
        }
    }

    @Test
    void listOfPojoParamSchemaShouldBeArrayWithObjectItems() {
        ToolRegistry registry = new ToolRegistry();
        registry.addAdapter(new AnnotationToolAdapter());
        List<Tool> tools = registry.registerTool(new BatchTool());
        Tool tool = tools.get(0);

        Map<String, Object> schema = tool.getParameters().getParametersSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> itemsProp = (Map<String, Object>) props.get("items");

        assertEquals("array", itemsProp.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> itemsSchema = (Map<String, Object>) itemsProp.get("items");
        assertEquals("object", itemsSchema.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> itemProps = (Map<String, Object>) itemsSchema.get("properties");
        assertNotNull(itemProps.get("name"));
        assertNotNull(itemProps.get("qty"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listOfPojoExecutionShouldConvertRawListToPojoList() {
        ToolRegistry registry = new ToolRegistry();
        registry.addAdapter(new AnnotationToolAdapter());
        List<Tool> tools = registry.registerTool(new BatchTool());
        Tool tool = tools.get(0);

        Map<String, Object> item1 = Map.of("name", "apple", "qty", 3);
        Map<String, Object> item2 = Map.of("name", "banana", "qty", 5);
        Map<String, Object> args = Map.of("items", List.of(item1, item2));

        ToolCallContext ctx = ToolCallContext.builder()
            .callId("cx").toolName("batch_create")
            .arguments(args)
            .tenantId("tx").userId("ux")
            .build();

        ToolResult result = tool.execute(ctx).block();
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("count=2"));
        assertTrue(result.getContent().contains("first=apple"));
    }
}
