package cn.iocoder.yudao.module.ai.service.pharmacy;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.ai.enums.model.AiModelTypeEnum;
import cn.iocoder.yudao.module.ai.service.model.AiModelService;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.base.DrugDO;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.base.DrugMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 基于 Qdrant 的药品说明书知识库。
 *
 * 设计要点：
 * 1. 语料与商品的对应关系靠 drug_code，不靠数据库主键，因此同一份语料可以给不同租户各自建索引；
 * 2. 片段 id 由「租户 + 药品 + 小节」派生为确定的 UUID，重复重建是覆盖而不是重复插入；
 * 3. 每个片段都带 tenantId 与 saleable 元数据，检索必须在服务端过滤，避免跨租户或处方药知识泄漏给顾客。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PharmacyKnowledgeServiceImpl implements PharmacyKnowledgeService {

    static final String KIND_DRUG = "drug_instruction";
    static final String KIND_TOPIC = "medication_topic";
    private static final int MAX_SECTION_CHARS = 280;

    private final AiModelService models;
    private final DrugMapper drugs;
    private final ObjectMapper json;
    private final QdrantVectorStoreProperties qdrantProperties;

    @Value("${firstsun.ai.knowledge.corpus-path:/knowledge/corpus.json}")
    private String corpusPath;
    @Value("${firstsun.ai.knowledge.top-k:4}")
    private int defaultTopK;
    @Value("${firstsun.ai.knowledge.threshold:0.28}")
    private double threshold;

    // ==================== 重建索引 ====================

    @Override
    public ReindexResult reindex() {
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        Corpus corpus = loadCorpus();
        Map<String, DrugDO> byCode = drugs.selectList().stream()
                .filter(d -> StrUtil.isNotBlank(d.getDrugCode()))
                .collect(Collectors.toMap(DrugDO::getDrugCode, d -> d, (a, b) -> a));

        List<Document> documents = new ArrayList<>();
        int matchedDrugs = 0;
        for (CorpusDrug drug : corpus.drugs()) {
            DrugDO row = byCode.get(drug.drugCode());
            if (row == null) continue; // 该租户没有这条商品，跳过，不跨租户写入
            matchedDrugs++;
            boolean saleable = saleable(row);
            String displayName = displayName(row);
            for (CorpusSection section : drug.sections()) {
                for (String chunk : split(section.text())) {
                    documents.add(document(tenantId, row.getId(), KIND_DRUG, drug.drugCode(), displayName,
                            section.key(), section.title(), chunk, saleable));
                }
            }
        }
        for (CorpusTopic topic : corpus.topics()) {
            for (String chunk : split(topic.text())) {
                documents.add(document(tenantId, null, KIND_TOPIC, topic.key(), null,
                        topic.key(), topic.title(), chunk, true));
            }
        }
        if (documents.isEmpty()) {
            log.warn("[reindex] 语料与租户 {} 的商品无交集，未写入任何片段", tenantId);
            return new ReindexResult(tenantId, 0, 0, false);
        }
        vectorStore().add(documents);
        log.info("[reindex] 租户 {} 写入 {} 条片段，命中 {} 条商品", tenantId, documents.size(), matchedDrugs);
        return new ReindexResult(tenantId, matchedDrugs, documents.size(), true);
    }

    // ==================== 检索 ====================

    @Override
    public List<Hit> search(String question, int topK, boolean saleableOnly) {
        if (StrUtil.isBlank(question)) return List.of();
        Long tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) return List.of();
        int limit = topK > 0 ? topK : defaultTopK;
        Filter.Expression filter = filter(tenantId, saleableOnly);
        List<Document> found;
        try {
            found = vectorStore().similaritySearch(SearchRequest.builder()
                    .query(question)
                    .topK(limit)
                    .similarityThreshold(threshold)
                    .filterExpression(filter)
                    .build());
        } catch (Exception e) {
            // 向量库不可用时不能拖垮问药主链路：退货空结果，由上层按“无资料”处理
            log.warn("[search] 向量检索失败，按无召回处理: {}", e.getMessage());
            return List.of();
        }
        return found.stream().map(d -> new Hit(
                longValue(d.getMetadata().get("drugId")),
                stringValue(d.getMetadata().get("drugName")),
                stringValue(d.getMetadata().get("sectionTitle")),
                d.getText(),
                d.getScore())).toList();
    }

    @Override
    public OptionalLong countPoints() {
        try (QdrantClient client = new QdrantClient(QdrantGrpcClient.newBuilder(
                qdrantProperties.getHost(), qdrantProperties.getPort(), qdrantProperties.isUseTls()).build())) {
            Long count = client.countAsync(qdrantProperties.getCollectionName()).get();
            return count == null ? OptionalLong.empty() : OptionalLong.of(count);
        } catch (Exception e) {
            log.debug("[countPoints] 读取 Qdrant 集合失败: {}", e.getMessage());
            return OptionalLong.empty();
        }
    }

    private Filter.Expression filter(Long tenantId, boolean saleableOnly) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        if (saleableOnly) {
            // Qdrant 只接受字符串/数值做相等过滤（布尔会抛 Invalid value type for EQ）；
            // 而 Spring AI 写入 payload 时会把元数据统一转成字符串，所以这里也按字符串比较。
            return b.and(b.eq("tenantId", String.valueOf(tenantId)), b.eq("saleable", "1")).build();
        }
        return b.eq("tenantId", String.valueOf(tenantId)).build();
    }

    private VectorStore vectorStore() {
        var model = models.getRequiredDefaultModel(AiModelTypeEnum.EMBEDDING.getType());
        return models.getOrCreateVectorStore(model.getId(), Map.of("tenantId", String.class,
                "drugId", String.class, "saleable", String.class));
    }

    // ==================== 文档构造 ====================

    private Document document(Long tenantId, Long drugId, String kind, String code, String drugName,
                              String sectionKey, String sectionTitle, String text, boolean saleable) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenantId", String.valueOf(tenantId));
        metadata.put("kind", kind);
        metadata.put("drugCode", code);
        metadata.put("section", sectionKey);
        metadata.put("sectionTitle", sectionTitle);
        metadata.put("saleable", saleable ? "1" : "0");
        if (drugId != null) metadata.put("drugId", drugId);
        if (drugName != null) metadata.put("drugName", drugName);
        // 正文带上药品名与小节名，向量里才有检索所需的语义（片段本身可能不含药名）
        String body = (drugName == null ? "" : drugName + " ") + sectionTitle + "：" + text;
        return new Document(pointId(tenantId, code, sectionKey, text), body, metadata);
    }

    static String pointId(Long tenantId, String code, String sectionKey, String text) {
        String seed = "firstsun:knowledge:" + tenantId + ":" + code + ":" + sectionKey + ":" + text.hashCode();
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** 长段落按句号切分，保证单个片段不超过上限，避免召回时上下文过粗。 */
    static List<String> split(String text) {
        if (StrUtil.isBlank(text)) return List.of();
        String normalized = text.strip();
        if (normalized.length() <= MAX_SECTION_CHARS) return List.of(normalized);
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : normalized.split("(?<=[。！？；])")) {
            if (current.length() + sentence.length() > MAX_SECTION_CHARS && current.length() > 0) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            current.append(sentence);
        }
        if (current.length() > 0) chunks.add(current.toString());
        return chunks;
    }

    /** 与顾客端商品可见性完全一致的判定：只有这些商品的知识可以出现在顾客回答里。 */
    static boolean saleable(DrugDO drug) {
        return Objects.equals(drug.getStatus(), 1) && Objects.equals(drug.getApproveStatus(), 1)
                && Objects.equals(drug.getSaleableOnline(), 1) && Objects.equals(drug.getIsRx(), 0)
                && Objects.equals(drug.getIsSpecial(), 0) && Objects.equals(drug.getIsPseudoephedrine(), 0)
                && (Objects.equals(drug.getDrugType(), 1) || Objects.equals(drug.getDrugType(), 2));
    }

    static String displayName(DrugDO drug) {
        String generic = drug.getGenericName(), trade = drug.getTradeName();
        if (StrUtil.isBlank(generic)) return trade;
        if (StrUtil.isBlank(trade) || generic.equals(trade)) return generic;
        return generic + "（" + trade + "）";
    }

    // ==================== 语料读取 ====================

    Corpus loadCorpus() {
        try {
            String content;
            Path path = Path.of(corpusPath);
            if (Files.exists(path)) {
                content = Files.readString(path, StandardCharsets.UTF_8);
            } else {
                content = new String(new ClassPathResource("knowledge/corpus.json").getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8);
            }
            return json.readValue(content, Corpus.class);
        } catch (Exception e) {
            throw new IllegalStateException("说明书语料读取失败：" + corpusPath + "，" + e.getMessage());
        }
    }

    record Corpus(String version, String source, List<CorpusDrug> drugs, List<CorpusTopic> topics) { }

    record CorpusDrug(String drugCode, String name, List<CorpusSection> sections) { }

    record CorpusSection(String key, String title, String text) { }

    record CorpusTopic(String key, String title, String text) { }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 仅用于日志/排查：Qdrant 集合的维度信息。 */
    String collectionName() {
        return qdrantProperties.getCollectionName();
    }
}
