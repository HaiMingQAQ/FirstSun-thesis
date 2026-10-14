package cn.iocoder.yudao.module.ai.service.pharmacy;

import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.pharmacy.controller.admin.purchase.vo.order.PurchaseOrderSaveReqVO;
import cn.iocoder.yudao.module.pharmacy.service.base.StoreService;
import cn.iocoder.yudao.module.pharmacy.service.inventory.InventoryAlertService;
import cn.iocoder.yudao.module.pharmacy.service.inventory.InventoryReadService;
import cn.iocoder.yudao.module.pharmacy.service.purchase.PurchaseInventoryReferenceAccess;
import cn.iocoder.yudao.module.pharmacy.service.purchase.PurchaseOrderService;
import cn.iocoder.yudao.module.pharmacy.service.purchase.SupplierService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.invalidParamException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Read-only procurement preview; confirmation delegates to the existing draft service. */
@Service
@RequiredArgsConstructor
public class PharmacyAiPurchaseService {
    public static final String PERMISSION = "pharmacy:purchase:order:create";
    private static final Set<String> FIELDS = Set.of("supplierId", "warehouseId", "orderDate", "expectDate", "remark", "lines");
    private static final Set<String> LINE_FIELDS = Set.of("drugId", "orderQty", "unitPrice", "discountRate", "remark");
    private final PharmacyAiContextService contexts;
    private final SecurityFrameworkService security;
    private final InventoryAlertService inventory;
    private final SupplierService suppliers;
    private final StoreService stores;
    private final InventoryReadService inventoryRead;
    private final PurchaseInventoryReferenceAccess references;
    private final PurchaseOrderService orders;
    private final ObjectMapper json;
    private final Validator validator;

    public Prepared prepare(JsonNode arguments) {
        if (arguments == null || !arguments.isObject() || arguments.size() != 1 || !arguments.has("purchase"))
            throw invalidParamException("请提供 purchase 对象：供应商、下单日期和明细数量/采购单价");
        JsonNode input = arguments.get("purchase");
        checkFields(input, FIELDS);
        if (!input.hasNonNull("supplierId") || !input.hasNonNull("orderDate") || !input.path("lines").isArray() || input.path("lines").isEmpty())
            throw invalidParamException("缺少供应商、下单日期或采购明细，请补齐；不会自动选择供应商或采购数量");
        if (input.get("lines").size() > 20) throw invalidParamException("单次采购预览最多 20 行");
        for (JsonNode line : input.get("lines")) {
            checkFields(line, LINE_FIELDS);
            if (!line.hasNonNull("drugId") || !line.hasNonNull("orderQty") || !line.hasNonNull("unitPrice"))
                throw invalidParamException("每行需要药品编号、订购数量和含税采购单价；不会自动填充数量或金额");
            if (!line.get("drugId").isIntegralNumber() || !line.get("drugId").canConvertToLong()
                    || !line.get("orderQty").isIntegralNumber() || !line.get("orderQty").canConvertToInt()
                    || !line.get("unitPrice").isNumber()) throw invalidParamException("药品编号、数量和价格必须是合法数值");
        }
        if (!input.get("supplierId").isIntegralNumber() || !input.get("supplierId").canConvertToLong()
                || (input.hasNonNull("warehouseId") && (!input.get("warehouseId").isIntegralNumber() || !input.get("warehouseId").canConvertToLong())))
            throw invalidParamException("供应商和仓库编号必须是整数");
        PurchaseOrderSaveReqVO request;
        try { request = json.treeToValue(input, PurchaseOrderSaveReqVO.class); }
        catch (Exception e) { throw invalidParamException("采购参数格式错误，日期须为 YYYY-MM-DD"); }
        request.setStoreId(contexts.requireContext(true).storeId());
        request.setIsAuto(0);
        var violations = validator.validate(request);
        if (!violations.isEmpty()) throw invalidParamException("采购信息不完整或不合法：" + violations.stream()
                .map(v -> v.getMessage()).sorted().distinct().collect(java.util.stream.Collectors.joining("；")));
        JsonNode snapshot = snapshot(request);
        ObjectNode after = json.createObjectNode();
        after.set("request", json.valueToTree(request)); after.set("display", snapshot);
        return new Prepared(json.valueToTree(request), snapshot, after);
    }

    public JsonNode snapshot(PurchaseOrderSaveReqVO request) {
        require(PERMISSION); require(PharmacyAiToolService.INVENTORY_QUERY);
        var context = contexts.requireContext(true);
        if (!context.storeId().equals(request.getStoreId())) throw new AccessDeniedException("采购预览不属于当前门店");
        var supplier = suppliers.validateSupplierPurchasable(request.getSupplierId());
        var store = stores.getStore(context.storeId());
        if (store == null || !Integer.valueOf(1).equals(store.getStatus())) throw invalidParamException("当前门店未启用");
        references.validateOrderWarehouse(context.storeId(), request.getWarehouseId());
        Map<String,Object> display = new LinkedHashMap<>();
        display.put("storeId", context.storeId()); display.put("storeName", store.getStoreName());
        display.put("supplier", Map.of("id", supplier.getId(), "name", supplier.getSupplierName(), "status", supplier.getStatus(), "approveStatus", supplier.getApproveStatus()));
        display.put("warehouse", request.getWarehouseId() == null ? "未指定，收货时须确定" : inventoryRead.warehouse(request.getWarehouseId()));
        var lines = new ArrayList<Map<String,Object>>();
        BigDecimal total = BigDecimal.ZERO, payable = BigDecimal.ZERO;
        for (var line : request.getLines()) {
            var stock = inventory.stock(line.getDrugId());
            if (!stock.isLowStock()) throw invalidParamException("药品 " + line.getDrugId() + " 当前不低于已配置库存下限，请重新查询预警");
            BigDecimal gross = line.getUnitPrice().multiply(BigDecimal.valueOf(line.getOrderQty())).setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = line.getUnitPrice().multiply(BigDecimal.valueOf(line.getOrderQty()))
                    .multiply(line.getDiscountRate() == null ? BigDecimal.ONE : line.getDiscountRate()).setScale(2, RoundingMode.HALF_UP);
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("stock", stock); row.put("orderQty", line.getOrderQty()); row.put("unitPrice", line.getUnitPrice());
            row.put("discountRate", line.getDiscountRate() == null ? BigDecimal.ONE : line.getDiscountRate()); row.put("amount", net);
            total = total.add(gross); payable = payable.add(net); lines.add(row);
        }
        display.put("lines", lines); display.put("totalAmount", total); display.put("discountAmount", total.subtract(payable));
        display.put("payableAmount", payable); display.put("orderDate", request.getOrderDate()); display.put("expectDate", request.getExpectDate());
        display.put("status", "DRAFT");
        return json.valueToTree(display);
    }

    public Long createDraft(JsonNode normalizedRequest) {
        require(PERMISSION);
        return orders.createOrder(json.convertValue(normalizedRequest, PurchaseOrderSaveReqVO.class));
    }

    private void require(String permission) { if (!security.hasPermission(permission)) throw new AccessDeniedException("缺少权限：" + permission); }
    private void checkFields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) throw invalidParamException("采购参数必须是对象");
        node.fieldNames().forEachRemaining(k -> { if (!allowed.contains(k)) throw invalidParamException("禁止或未知采购字段：" + k); });
    }
    public record Prepared(JsonNode request, JsonNode before, JsonNode after) { }
}
