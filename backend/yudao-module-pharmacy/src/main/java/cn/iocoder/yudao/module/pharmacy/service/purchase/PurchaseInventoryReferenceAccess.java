package cn.iocoder.yudao.module.pharmacy.service.purchase;

import cn.iocoder.yudao.module.pharmacy.dal.mysql.inventory.InventoryReadMapper;
import cn.iocoder.yudao.module.pharmacy.service.inventory.InventoryReadAccess;
import cn.iocoder.yudao.module.pharmacy.service.inventory.InventoryReadAccess.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.invalidParamException;

/** 保存采购草稿时检查库存引用；入账时库存门面仍会复核。 */
@Service
@RequiredArgsConstructor
public class PurchaseInventoryReferenceAccess {

    private final InventoryReadAccess access;
    private final InventoryReadMapper mapper;

    public void validateOrderWarehouse(Long storeId, Long warehouseId) {
        if (warehouseId != null) {
            validateWarehouse(access.requireScope(storeId), warehouseId);
        }
    }

    public void validateReceiptReferences(Long storeId, Long warehouseId, Collection<Long> locationIds) {
        if (warehouseId == null) {
            throw invalidParamException("收货仓库不能为空");
        }
        Scope scope = access.requireScope(storeId);
        validateWarehouse(scope, warehouseId);
        locationIds.stream().filter(Objects::nonNull).distinct().forEach(locationId -> {
            var location = mapper.selectLocation(scope, warehouseId, locationId);
            if (location == null || !Objects.equals(location.getWarehouseId(), warehouseId)
                    || !Objects.equals(location.getStatus(), 1)) {
                throw invalidParamException("收货货位不属于当前租户、门店和仓库，或未启用");
            }
        });
    }

    private void validateWarehouse(Scope scope, Long warehouseId) {
        var warehouse = mapper.selectWarehouse(scope, warehouseId);
        if (warehouse == null || !Objects.equals(warehouse.getStoreId(), scope.storeId())
                || !Objects.equals(warehouse.getStatus(), 1)) {
            throw invalidParamException("采购仓库不属于当前租户门店，或未启用");
        }
    }
}
