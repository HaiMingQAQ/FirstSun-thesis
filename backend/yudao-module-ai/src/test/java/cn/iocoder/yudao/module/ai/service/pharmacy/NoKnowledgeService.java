package cn.iocoder.yudao.module.ai.service.pharmacy;

import java.util.List;
import java.util.OptionalLong;

/**
 * 单测用的空知识库：不召回任何片段，用来验证「无 RAG 证据」时提示词与守卫仍按原逻辑工作。
 * 需要验证检索行为的用例请自己构造带命中结果的实现。
 */
final class NoKnowledgeService implements PharmacyKnowledgeService {

    static final NoKnowledgeService INSTANCE = new NoKnowledgeService();

    private NoKnowledgeService() { }

    @Override
    public ReindexResult reindex() {
        return new ReindexResult(0L, 0, 0, false);
    }

    @Override
    public List<Hit> search(String question, int topK, boolean saleableOnly) {
        return List.of();
    }

    @Override
    public OptionalLong countPoints() {
        return OptionalLong.empty();
    }
}
