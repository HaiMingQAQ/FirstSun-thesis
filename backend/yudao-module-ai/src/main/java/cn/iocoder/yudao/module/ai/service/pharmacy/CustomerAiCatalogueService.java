package cn.iocoder.yudao.module.ai.service.pharmacy;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.ai.controller.app.pharmacy.vo.CustomerAiConsultRespVO.Product;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.base.DrugDO;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.base.DrugMapper;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.inventory.AppAvailableInventoryMapper;
import cn.iocoder.yudao.module.pharmacy.service.base.StoreService;
import cn.iocoder.yudao.module.pharmacy.service.base.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;
@Service @RequiredArgsConstructor
public class CustomerAiCatalogueService {
    private final DrugMapper drugs;
    private final AppAvailableInventoryMapper inventory;
    private final StoreService stores;
    private final CategoryService categories;
    public record Result(List<Product> products, boolean truncated) { }
    public void requireStore(Long storeId) {
        var store = stores.getStore(storeId);
        if (store == null || !Objects.equals(store.getStatus(), 1))
            throw new AccessDeniedException("门店不存在或不可用");
    }
    public Result query(Long storeId, String keyword, Long id) {
        return query(storeId, keyword, id, false, List.of());
    }
    public Result adviceCandidates(Long storeId, String symptom) {
        requireStore(storeId);
        if (symptom == null || symptom.isBlank()) return new Result(List.of(), false);
        var terms = symptomTerms(symptom);
        if (terms.isEmpty()) terms = List.of(symptom.trim());
        var searchTerms = terms;
        var query = allowedQuery().and(w -> {
            for (int i = 0; i < searchTerms.size(); i++) {
                if (i > 0) w.or();
                w.like(DrugDO::getDescription, searchTerms.get(i));
            }
        }).orderByDesc(DrugDO::getId).last("LIMIT 61");
        var found = drugs.selectList(query);
        // A mention in contraindications/source notes or a category name is not an indication.
        var matched = found.stream().filter(d -> searchTerms.stream().anyMatch(t -> indications(d.getDescription()).contains(t))).toList();
        return project(storeId, matched, true, found.size() > 60);
    }
    static List<String> symptomTerms(String text) {
        if (text == null) return List.of();
        var terms = new LinkedHashSet<String>();
        for (var group : List.of(List.of("流鼻涕", "流涕"), List.of("打喷嚏", "喷嚏"), List.of("鼻塞"),
                List.of("头疼", "头痛"), List.of("发烧", "发热"), List.of("嗓子疼", "咽痛"), List.of("咳嗽"), List.of("感冒")))
            if (group.stream().anyMatch(word -> java.util.regex.Pattern.compile("(?<!没有)(?<!没有明显)(?<!没)(?<!无)(?<!不)(?<!未)(?<!否认)" + java.util.regex.Pattern.quote(word)).matcher(text).find())) terms.addAll(group);
        return List.copyOf(terms);
    }
    static String indications(String description) {
        if (description == null) return "";
        var line = java.util.regex.Pattern.compile("(?m)^(?:适应症(?:/功能主治)?|功能主治|适用范围)[：:]\\s*([^\\r\\n]+)").matcher(description);
        return line.find() ? line.group(1) : "";
    }
    private LambdaQueryWrapperX<DrugDO> allowedQuery() {
        var query = new LambdaQueryWrapperX<DrugDO>();
        query
                .eq(DrugDO::getStatus, 1).eq(DrugDO::getApproveStatus, 1).eq(DrugDO::getSaleableOnline, 1)
                .eq(DrugDO::getIsRx, 0).eq(DrugDO::getIsSpecial, 0).eq(DrugDO::getIsPseudoephedrine, 0)
                .in(DrugDO::getDrugType, 1, 2);
        return query;
    }
    public Result browseCandidates(Long storeId, String keyword) {
        requireStore(storeId);
        if (keyword == null || keyword.isBlank()) return new Result(List.of(), false);
        var categoryIds = categories.getEnabledCategoryList().stream()
                .filter(c -> c.getCatName() != null && c.getCatName().contains(keyword)).map(c -> c.getId()).toList();
        return query(storeId, keyword, null, true, categoryIds);
    }
    private Result query(Long storeId, String keyword, Long id, boolean advice, List<Long> categoryIds) {
        requireStore(storeId);
        // One bounded projection; filter BEFORE limiting. Never reuse administrator search results.
        var query = allowedQuery()
                .eqIfPresent(DrugDO::getId, id)
                .and(!advice && keyword != null && !keyword.isBlank(), w -> w.like(DrugDO::getGenericName, keyword)
                        .or().like(DrugDO::getTradeName, keyword).or().like(DrugDO::getSpellCode, keyword))
                .and(advice, w -> w.like(DrugDO::getDescription, keyword)
                        .or().like(DrugDO::getGenericName, keyword).or().like(DrugDO::getTradeName, keyword)
                        .or(!categoryIds.isEmpty()).in(!categoryIds.isEmpty(), DrugDO::getCategoryId, categoryIds))
                .orderByDesc(DrugDO::getId).last("LIMIT 21");
        var found = drugs.selectList(query);
        return project(storeId, found, false, found.size() > 20);
    }
    private Result project(Long storeId, List<DrugDO> found, boolean includeUnavailable, boolean truncated) {
        if (found.isEmpty()) return new Result(List.of(), false);
        var stocks = inventory.selectAvailable(TenantContextHolder.getTenantId(), storeId,
                found.stream().map(DrugDO::getId).toList()).stream()
                .collect(Collectors.toMap(v -> v.getDrugId(), v -> v.getQtyAvail(), (a,b) -> a));
        var products = found.stream().filter(d -> includeUnavailable || stocks.getOrDefault(d.getId(), 0) > 0)
                .sorted(Comparator.comparingInt(d -> stocks.getOrDefault(d.getId(), 0) > 0 ? 0 : 1)).limit(20)
                .map(d -> new Product(d.getId(), displayName(d),
                        d.getGenericName(), d.getSpecification(), d.getManufacturer(), d.getApprovalNo(), d.getImageUrl(),
                        d.getMemberPrice() == null ? d.getRetailPrice() : d.getMemberPrice(), Math.max(0, stocks.getOrDefault(d.getId(), 0)), storeId,
                        d.getDescription())).toList();
        return new Result(products, truncated || (includeUnavailable && found.size() > 20));
    }
    static String displayName(DrugDO drug) {
        String generic = drug.getGenericName(), trade = drug.getTradeName();
        if (generic == null || generic.isBlank()) return trade;
        if (trade == null || trade.isBlank() || generic.equals(trade)) return generic;
        return generic + "（" + trade + "）";
    }
}
