package cd.lan1akea.core.memory;

import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 向量嵌入服务接口（预留）。
 * <p>
 * 将文本转换为向量表示，用于语义搜索。
 * </p>
 */
public interface EmbeddingService {

    /**
     * 生成文本嵌入向量。
     *
     * @param text 输入文本
     * @return Mono&lt;List&lt;Float&gt;&gt; 向量
     */
    Mono<List<Float>> embed(String text);

    /**
     * 批量生成嵌入向量。
     *
     * @param texts 输入文本列表
     * @return Mono&lt;List&lt;List&lt;Float&gt;&gt;&gt; 向量列表
     */
    Mono<List<List<Float>>> embedBatch(List<String> texts);
}
