package cd.lan1akea.core.memory;

/**
 * 长期记忆模式枚举。
 */
public enum LongTermMemoryMode {

    /** 语义搜索（基于嵌入向量） */
    SEMANTIC,

    /** 关键词搜索 */
    KEYWORD,

    /** 摘要模式（返回记忆摘要而非原始条目） */
    SUMMARY
}
