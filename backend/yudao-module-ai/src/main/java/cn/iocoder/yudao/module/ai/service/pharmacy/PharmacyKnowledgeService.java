package cn.iocoder.yudao.module.ai.service.pharmacy;

import java.util.List;

/**
 * 药品说明书知识库（RAG 检索）。
 *
 * 语料为版本化的说明书片段（deploy/knowledge/corpus.json），重建索引时切分、
 * 向量化并写入 Qdrant；检索时按租户隔离，顾客侧只召回可在线销售的非处方药。
 */
public interface PharmacyKnowledgeService {

    /** 检索命中的说明书片段。 */
    record Hit(Long drugId, String drugName, String section, String text, Double score) {

        /** 供提示词引用的紧凑文本，例如「板蓝根颗粒·禁忌」。 */
        public String label() {
            return drugName == null ? section : drugName + "·" + section;
        }
    }

    /** 重建结果。 */
    record ReindexResult(Long tenantId, int drugs, int chunks, boolean vectorStore) { }

    /**
     * 按当前租户上下文重建知识库索引（幂等：片段 id 由租户+药品+小节决定，重复执行覆盖写入）。
     */
    ReindexResult reindex();

    /**
     * 检索与问题相关的说明书片段。
     *
     * @param question  顾客问题
     * @param topK      召回条数，<=0 时使用配置默认值
     * @param saleableOnly 只召回可在线销售的非处方药（顾客侧必须为 true）
     */
    List<Hit> search(String question, int topK, boolean saleableOnly);

    /** 向量库集合中的片段总数；库不可读时返回 empty（用于自检与演示，不影响主链路）。 */
    java.util.OptionalLong countPoints();
}
