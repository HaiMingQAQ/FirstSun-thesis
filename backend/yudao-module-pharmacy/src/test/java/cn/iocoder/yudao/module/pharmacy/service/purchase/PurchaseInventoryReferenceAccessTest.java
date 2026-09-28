package cn.iocoder.yudao.module.pharmacy.service.purchase;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.pharmacy.controller.admin.inventory.vo.InventoryReadVO;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.inventory.InventoryReadMapper;
import cn.iocoder.yudao.module.pharmacy.service.inventory.InventoryReadAccess;
import cn.iocoder.yudao.module.pharmacy.service.inventory.InventoryReadAccess.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PurchaseInventoryReferenceAccessTest {

    @Mock
    private InventoryReadAccess access;
    @Mock
    private InventoryReadMapper mapper;
    @InjectMocks
    private PurchaseInventoryReferenceAccess references;

    private final Scope storeScope = new Scope(11L, 101L);

    @Test
    void orderMayLeaveWarehouseUnset() {
        assertDoesNotThrow(() -> references.validateOrderWarehouse(101L, null));
        verifyNoInteractions(access, mapper);
    }

    @Test
    void orderWarehouseMustBelongToCurrentTenantAndStoreAndBeEnabled() {
        when(access.requireScope(101L)).thenReturn(storeScope);
        when(mapper.selectWarehouse(storeScope, 501L)).thenReturn(warehouse(501L, 1));

        assertDoesNotThrow(() -> references.validateOrderWarehouse(101L, 501L));
        // 502 位于同租户其他门店，503 位于其他租户：带当前 scope 的查询均不得返回记录。
        assertThrows(ServiceException.class, () -> references.validateOrderWarehouse(101L, 502L));
        assertThrows(ServiceException.class, () -> references.validateOrderWarehouse(101L, 503L));
        verify(mapper).selectWarehouse(storeScope, 502L);
        verify(mapper).selectWarehouse(storeScope, 503L);
        InventoryReadVO.Warehouse foreignStore = warehouse(504L, 1);
        foreignStore.setStoreId(102L);
        when(mapper.selectWarehouse(storeScope, 504L)).thenReturn(foreignStore);
        assertThrows(ServiceException.class, () -> references.validateOrderWarehouse(101L, 504L));
    }

    @Test
    void receiptWarehouseIsRequiredAndMustBeEnabled() {
        assertThrows(ServiceException.class,
                () -> references.validateReceiptReferences(101L, null, List.of()));
        verifyNoInteractions(access, mapper);

        when(access.requireScope(101L)).thenReturn(storeScope);
        when(mapper.selectWarehouse(storeScope, 501L)).thenReturn(warehouse(501L, 0));
        assertThrows(ServiceException.class,
                () -> references.validateReceiptReferences(101L, 501L, List.of()));
    }

    @Test
    void receiptLocationMustBelongToSelectedWarehouseAndCurrentTenantStore() {
        when(access.requireScope(101L)).thenReturn(storeScope);
        when(mapper.selectWarehouse(storeScope, 501L)).thenReturn(warehouse(501L, 1));
        when(mapper.selectLocation(storeScope, 501L, 701L)).thenReturn(location(701L, 501L, 1));

        assertDoesNotThrow(() -> references.validateReceiptReferences(101L, 501L,
                Arrays.asList(701L, 701L, null)));
        verify(mapper, times(1)).selectLocation(storeScope, 501L, 701L);

        // 702 属于另一仓库，703 属于同租户另一门店，704 属于其他租户。
        for (long foreignId : List.of(702L, 703L, 704L)) {
            assertThrows(ServiceException.class,
                    () -> references.validateReceiptReferences(101L, 501L, List.of(foreignId)));
            verify(mapper).selectLocation(storeScope, 501L, foreignId);
        }
        when(mapper.selectLocation(storeScope, 501L, 705L)).thenReturn(location(705L, 502L, 1));
        assertThrows(ServiceException.class,
                () -> references.validateReceiptReferences(101L, 501L, List.of(705L)));
    }

    private InventoryReadVO.Warehouse warehouse(Long id, int status) {
        InventoryReadVO.Warehouse row = new InventoryReadVO.Warehouse();
        row.setId(id);
        row.setStoreId(101L);
        row.setStatus(status);
        return row;
    }

    private InventoryReadVO.Location location(Long id, Long warehouseId, int status) {
        InventoryReadVO.Location row = new InventoryReadVO.Location();
        row.setId(id);
        row.setWarehouseId(warehouseId);
        row.setStatus(status);
        return row;
    }
}
