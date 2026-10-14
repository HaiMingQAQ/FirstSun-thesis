package cn.iocoder.yudao.module.ai.service.pharmacy;

import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.base.StoreDO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.purchase.SupplierDO;
import cn.iocoder.yudao.module.pharmacy.service.base.StoreService;
import cn.iocoder.yudao.module.pharmacy.service.inventory.InventoryAlertService;
import cn.iocoder.yudao.module.pharmacy.service.inventory.InventoryReadService;
import cn.iocoder.yudao.module.pharmacy.service.purchase.PurchaseInventoryReferenceAccess;
import cn.iocoder.yudao.module.pharmacy.service.purchase.PurchaseOrderService;
import cn.iocoder.yudao.module.pharmacy.service.purchase.SupplierService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.math.BigDecimal;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PharmacyAiPurchaseServiceTest {
    private final PharmacyAiContextService contexts = mock(PharmacyAiContextService.class);
    private final SecurityFrameworkService security = mock(SecurityFrameworkService.class);
    private final InventoryAlertService inventory = mock(InventoryAlertService.class);
    private final SupplierService suppliers = mock(SupplierService.class);
    private final StoreService stores = mock(StoreService.class);
    private final InventoryReadService read = mock(InventoryReadService.class);
    private final PurchaseInventoryReferenceAccess references = mock(PurchaseInventoryReferenceAccess.class);
    private final PurchaseOrderService orders = mock(PurchaseOrderService.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private PharmacyAiPurchaseService service;
    private InventoryAlertService.Stock stock;

    @BeforeEach void setup() {
        service = new PharmacyAiPurchaseService(contexts, security, inventory, suppliers, stores, read, references, orders,
                json, Validation.buildDefaultValidatorFactory().getValidator());
        when(contexts.requireContext(true)).thenReturn(new PharmacyAiContextService.Context(1L,11L,21L,31L));
        when(security.hasPermission(any())).thenReturn(true);
        var supplier = new SupplierDO(); supplier.setId(2L); supplier.setSupplierName("合成供应商"); supplier.setStatus(1); supplier.setApproveStatus(1);
        when(suppliers.validateSupplierPurchasable(2L)).thenReturn(supplier);
        var store = new StoreDO(); store.setId(31L); store.setStoreName("合成门店"); store.setStatus(1); when(stores.getStore(31L)).thenReturn(store);
        stock = new InventoryAlertService.Stock(); stock.setDrugId(1L); stock.setDrugName("合成药品"); stock.setMinStock(10); stock.setAvailableQty(2L);
        when(inventory.stock(1L)).thenAnswer(i -> stock);
    }
    private com.fasterxml.jackson.databind.JsonNode request() throws Exception {
        return json.readTree("""
                {"purchase":{"supplierId":2,"orderDate":"2026-10-12","lines":[{"drugId":1,"orderQty":5,"unitPrice":4.5,"discountRate":0.8}]}}
                """);
    }
    @Test void previewUsesExistingRuleAndUserQuantitiesWithoutCreatingDraft() throws Exception {
        var preview = service.prepare(request());
        assertEquals(31L, preview.request().path("storeId").asLong());
        assertEquals(5, preview.after().path("display").path("lines").get(0).path("orderQty").asInt());
        assertEquals(2, preview.before().path("lines").get(0).path("stock").path("availableQty").asInt());
        assertEquals(0, new BigDecimal("18.00").compareTo(preview.before().path("payableAmount").decimalValue()));
        assertEquals("DRAFT", preview.before().path("status").asText());
        verifyNoInteractions(orders);
    }
    @Test void missingRequiredValuesAreNotInvented() throws Exception {
        for (String invalid : new String[]{"{\"purchase\":{}}",
                "{\"purchase\":{\"supplierId\":2,\"orderDate\":\"2026-10-12\",\"lines\":[{\"drugId\":1,\"unitPrice\":2}]}}",
                "{\"purchase\":{\"supplierId\":2,\"orderDate\":\"2026-10-12\",\"lines\":[{\"drugId\":1,\"orderQty\":5}]}}"})
            assertThrows(ServiceException.class, () -> service.prepare(json.readTree(invalid)));
        verifyNoInteractions(orders);
    }
    @Test void storeStatusAndServerFieldsCannotBeInjected() throws Exception {
        var args = request(); ((com.fasterxml.jackson.databind.node.ObjectNode)args.get("purchase")).put("storeId",999);
        assertThrows(ServiceException.class, () -> service.prepare(args));
        verifyNoInteractions(orders);
    }
    @Test void noPurchaseOrInventoryPermissionMeansNoDraft() throws Exception {
        when(security.hasPermission(PharmacyAiPurchaseService.PERMISSION)).thenReturn(false);
        assertThrows(AccessDeniedException.class, () -> service.prepare(request()));
        when(security.hasPermission(PharmacyAiPurchaseService.PERMISSION)).thenReturn(true);
        when(security.hasPermission(PharmacyAiToolService.INVENTORY_QUERY)).thenReturn(false);
        assertThrows(AccessDeniedException.class, () -> service.prepare(request()));
        verifyNoInteractions(orders);
    }
    @Test void thresholdDisabledOrReachedCannotGenerateAlertPurchase() throws Exception {
        stock.setAvailableQty(10L);
        assertThrows(ServiceException.class, () -> service.prepare(request()));
        stock.setMinStock(0); stock.setAvailableQty(0L);
        assertThrows(ServiceException.class, () -> service.prepare(request()));
        verifyNoInteractions(orders);
    }
    @Test void warehouseStillUsesExistingStoreReferenceGate() throws Exception {
        var args = request(); ((com.fasterxml.jackson.databind.node.ObjectNode)args.get("purchase")).put("warehouseId",99);
        doThrow(new AccessDeniedException("跨店")).when(references).validateOrderWarehouse(31L,99L);
        assertThrows(AccessDeniedException.class, () -> service.prepare(args));
        verifyNoInteractions(orders);
    }
    @Test void negativeAndExcessPrecisionAmountsAreRejected() throws Exception {
        for (double amount : new double[]{-1, 1.234}) {
            var args = request(); ((com.fasterxml.jackson.databind.node.ObjectNode)args.path("purchase").path("lines").get(0)).put("unitPrice",amount);
            assertThrows(ServiceException.class, () -> service.prepare(args));
        }
        verifyNoInteractions(orders);
    }
}
