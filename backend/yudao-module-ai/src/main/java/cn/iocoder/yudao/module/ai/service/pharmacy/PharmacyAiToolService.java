package cn.iocoder.yudao.module.ai.service.pharmacy;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.pharmacy.controller.admin.inventory.vo.InventoryReadQuery;
import cn.iocoder.yudao.module.pharmacy.controller.admin.inventory.vo.InventoryReadVO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.base.DrugDO;
import cn.iocoder.yudao.module.pharmacy.service.base.DrugService;
import cn.iocoder.yudao.module.pharmacy.service.inventory.InventoryReadService;
import cn.iocoder.yudao.module.pharmacy.service.inventory.InventoryAlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PharmacyAiToolService {
    public static final String DRUG_QUERY = "pharmacy:base:drug:query";
    public static final String INVENTORY_QUERY = "pharmacy:inventory-batch:query";

    private final DrugService drugService;
    private final InventoryReadService inventoryReadService;
    private final PharmacyAiContextService contextService;
    private final SecurityFrameworkService security;
    private final InventoryAlertService inventoryAlerts;

    public Map<String, Object> getInventoryAlerts(Long drugId, String keyword, Integer requestedLimit) {
        require(INVENTORY_QUERY);
        var context = contextService.requireContext(true);
        int limit = Math.max(1, Math.min(requestedLimit == null ? 20 : requestedLimit, 20));
        var rows = inventoryAlerts.alerts(drugId, keyword, limit + 1);
        return Map.of("storeId", context.storeId(), "rule", "可用库存低于药品已配置下限 minStock；0 表示不启用下限预警",
                "items", rows.stream().limit(limit).toList(), "hasMore", rows.size() > limit);
    }

    public List<Map<String, Object>> searchDrugs(String keyword, Integer status, Integer requestedLimit) {
        require(DRUG_QUERY);
        int limit = Math.max(1, Math.min(requestedLimit == null ? 10 : requestedLimit, 20));
        return drugService.searchDrugs(keyword, status, limit).stream().map(this::drugSummary).toList();
    }

    public Map<String, Object> getDrugDetail(Long id, String drugCode) {
        require(DRUG_QUERY);
        DrugDO drug = id != null ? drugService.getDrug(id) : drugService.getDrugByCode(drugCode);
        return drug == null ? Map.of("found", false) : drugDetail(drug);
    }

    public Map<String, Object> getCurrentStoreInventory(Long drugId, String keyword) {
        require(INVENTORY_QUERY);
        var context = contextService.requireContext(true);
        List<DrugDO> drugs = drugId != null ? drugService.getDrugList(List.of(drugId))
                : drugService.searchDrugs(keyword, null, 10);
        List<Map<String, Object>> rows = drugs.stream().map(drug -> {
            InventoryReadQuery query = new InventoryReadQuery();
            query.setDrugId(drug.getId());
            query.setPageNo(1);
            query.setPageSize(20);
            PageResult<InventoryReadVO.Batch> batches = inventoryReadService.batches(query);
            long available = inventoryAlerts.stock(drug.getId()).getAvailableQty();
            int frozen = batches.getList().stream().mapToInt(v -> value(v.getQtyFrozen())).sum();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("drug", drugSummary(drug));
            row.put("availableQty", available);
            row.put("frozenQty", frozen);
            row.put("batches", batches.getList().stream().map(this::batchSummary).toList());
            row.put("batchNote", "批次台账明细可能含过期或质量异常批次；可用库存按有效且启用的货位库存减冻结量完整汇总");
            row.put("truncated", batches.getTotal() > batches.getList().size());
            return row;
        }).toList();
        return Map.of("storeId", context.storeId(), "items", rows);
    }

    public void require(String permission) {
        if (!security.hasPermission(permission)) {
            throw new AccessDeniedException("缺少权限：" + permission);
        }
    }

    private int value(Integer number) { return number == null ? 0 : number; }

    private Map<String, Object> batchSummary(InventoryReadVO.Batch batch) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchNo", batch.getBatchNo());
        result.put("expiryDate", batch.getExpiryDate());
        result.put("availableQty", batch.getQtyAvail());
        result.put("frozenQty", batch.getQtyFrozen());
        result.put("qualityStatus", batch.getQualityStatus());
        return result;
    }

    private Map<String, Object> drugSummary(DrugDO drug) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", drug.getId());
        result.put("drugCode", drug.getDrugCode());
        result.put("genericName", drug.getGenericName());
        result.put("tradeName", drug.getTradeName());
        result.put("specification", drug.getSpecification());
        result.put("manufacturer", drug.getManufacturer());
        result.put("status", drug.getStatus());
        return result;
    }

    private Map<String, Object> drugDetail(DrugDO drug) {
        Map<String, Object> result = new LinkedHashMap<>(drugSummary(drug));
        result.put("categoryId", drug.getCategoryId());
        result.put("dosageForm", drug.getDosageForm());
        result.put("unit", drug.getUnit());
        result.put("approvalNo", drug.getApprovalNo());
        result.put("retailPrice", drug.getRetailPrice());
        result.put("memberPrice", drug.getMemberPrice());
        result.put("minSalePrice", drug.getMinSalePrice());
        result.put("isRx", drug.getIsRx());
        result.put("storageCond", drug.getStorageCond());
        result.put("approveStatus", drug.getApproveStatus());
        return result;
    }
}
