package cn.iocoder.yudao.module.ai.service.pharmacy;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.ai.controller.app.pharmacy.vo.CustomerAiConsultRespVO.Product;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.base.DrugDO;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.base.DrugMapper;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.inventory.AppAvailableInventoryMapper;
import cn.iocoder.yudao.module.pharmacy.service.base.StoreService;
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
    public record Result(List<Product> products, boolean truncated) { }
    public void requireStore(Long storeId) {
        var store = stores.getStore(storeId);
        if (store == null || !Objects.equals(store.getStatus(), 1))
            throw new AccessDeniedException("门店不存在或不可用");
    }
    public Result query(Long storeId, String keyword, Long id) {
        return query(storeId, keyword, id, false);
    }
    public Result adviceCandidates(Long storeId, String symptom) {
        if (symptom == null || symptom.isBlank()) return new Result(List.of(), false);
        return query(storeId, symptom, null, true);
    }
    private Result query(Long storeId, String keyword, Long id, boolean advice) {
        requireStore(storeId);
        // One bounded projection; filter BEFORE limiting. Never reuse administrator search results.
        var query = new LambdaQueryWrapperX<DrugDO>()
                .eq(DrugDO::getStatus, 1).eq(DrugDO::getApproveStatus, 1).eq(DrugDO::getSaleableOnline, 1)
                .eq(DrugDO::getIsRx, 0).eq(DrugDO::getIsSpecial, 0).eq(DrugDO::getIsPseudoephedrine, 0)
                .eqIfPresent(DrugDO::getId, id)
                .in(DrugDO::getDrugType, 1, 2)
                .and(!advice && keyword != null && !keyword.isBlank(), w -> w.like(DrugDO::getGenericName, keyword)
                        .or().like(DrugDO::getTradeName, keyword).or().like(DrugDO::getSpellCode, keyword))
                .like(advice, DrugDO::getDescription, keyword)
                .orderByDesc(DrugDO::getId).last("LIMIT 21");
        var found = drugs.selectList(query);
        if (found.isEmpty()) return new Result(List.of(), false);
        var stocks = inventory.selectAvailable(TenantContextHolder.getTenantId(), storeId,
                found.stream().map(DrugDO::getId).toList()).stream()
                .collect(Collectors.toMap(v -> v.getDrugId(), v -> v.getQtyAvail(), (a,b) -> a));
        var products = found.stream().filter(d -> stocks.getOrDefault(d.getId(), 0) > 0).limit(20)
                .map(d -> new Product(d.getId(), displayName(d),
                        d.getGenericName(), d.getSpecification(), d.getManufacturer(), d.getApprovalNo(), d.getImageUrl(),
                        d.getMemberPrice() == null ? d.getRetailPrice() : d.getMemberPrice(), stocks.get(d.getId()), storeId,
                        d.getDescription())).toList();
        return new Result(products, found.size() > 20);
    }
    static String displayName(DrugDO drug) {
        String generic = drug.getGenericName(), trade = drug.getTradeName();
        if (generic == null || generic.isBlank()) return trade;
        if (trade == null || trade.isBlank() || generic.equals(trade)) return generic;
        return generic + "（" + trade + "）";
    }
}
