package cd.lan1akea.core.knowledge;

/**
 * 知识库匹配接口。由门面层实现注入 KnowledgeBaseHook。
 * 支持精确匹配、语义检索、RAG 等任意策略。
 */
@FunctionalInterface
public interface KnowledgeMatcher {

    /**
     * @param userInput 当前用户输入文本
     * @return 匹配的答案文本，null 表示未命中
     */
    String match(String userInput);
}
