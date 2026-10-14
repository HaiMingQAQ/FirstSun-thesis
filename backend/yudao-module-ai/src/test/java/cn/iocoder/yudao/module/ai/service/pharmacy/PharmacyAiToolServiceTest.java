package cn.iocoder.yudao.module.ai.service.pharmacy;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.base.DrugDO;
import cn.iocoder.yudao.module.pharmacy.service.base.DrugService;
import cn.iocoder.yudao.module.pharmacy.service.inventory.InventoryReadService;
import cn.iocoder.yudao.module.pharmacy.service.inventory.InventoryAlertService;
import cn.iocoder.yudao.module.pharmacy.controller.admin.inventory.vo.InventoryReadVO;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PharmacyAiToolServiceTest {
    @Test void usableStockDoesNotUseRawOrTruncatedBatchLedger() {
        var drugs=mock(DrugService.class); var read=mock(InventoryReadService.class);
        var context=mock(PharmacyAiContextService.class); var security=mock(SecurityFrameworkService.class);
        var inventory=mock(InventoryAlertService.class);
        var service=new PharmacyAiToolService(drugs,read,context,security,inventory);
        when(security.hasPermission(any())).thenReturn(true);
        when(context.requireContext(true)).thenReturn(new PharmacyAiContextService.Context(1L,11L,21L,31L));
        var drug=new DrugDO();drug.setId(1L);when(drugs.getDrugList(List.of(1L))).thenReturn(List.of(drug));
        var raw=new InventoryReadVO.Batch();raw.setQtyAvail(108);raw.setQtyFrozen(20);
        when(read.batches(any())).thenReturn(new PageResult<>(List.of(raw),30L));
        var stock=new InventoryAlertService.Stock();stock.setAvailableQty(10L);when(inventory.stock(1L)).thenReturn(stock);
        var row=(Map<?,?>)((List<?>)service.getCurrentStoreInventory(1L,null).get("items")).get(0);
        assertEquals(10L,row.get("availableQty"));assertEquals(true,row.get("truncated"));
        assertNotNull(row.get("batchNote"));verify(inventory).stock(1L);
    }
    @Test void alertPermissionFailureDoesNotReadStock() {
        var context=mock(PharmacyAiContextService.class);var security=mock(SecurityFrameworkService.class);
        var inventory=mock(InventoryAlertService.class);
        var service=new PharmacyAiToolService(mock(DrugService.class),mock(InventoryReadService.class),context,security,inventory);
        when(security.hasPermission(any())).thenReturn(false);
        assertThrows(AccessDeniedException.class,()->service.getInventoryAlerts(null,null,20));
        verifyNoInteractions(inventory,context);
    }
}
