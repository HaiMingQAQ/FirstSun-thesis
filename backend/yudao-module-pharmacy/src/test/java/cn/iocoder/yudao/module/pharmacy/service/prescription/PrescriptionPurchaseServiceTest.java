package cn.iocoder.yudao.module.pharmacy.service.prescription;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.controller.admin.prescription.vo.PrescItemVO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.base.*;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.*;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.prescription.*;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.prescription.*;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.member.WxOrderLineMapper;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.inventory.AppAvailableInventoryMapper;
import cn.iocoder.yudao.module.pharmacy.service.base.*;
import org.junit.jupiter.api.*;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PrescriptionPurchaseServiceTest {
    @BeforeAll static void initializeMapperMetadata() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),"prescription-use-test"),PrescriptionUseDO.class);
    }
    final PrescRecordMapper records=mock(PrescRecordMapper.class);
    final PrescriptionUseMapper uses=mock(PrescriptionUseMapper.class);
    final AppAvailableInventoryMapper stock=mock(AppAvailableInventoryMapper.class);
    final PrescriptionNotificationService notifications=mock(PrescriptionNotificationService.class);
    final StoreService stores=mock(StoreService.class);
    final DrugService drugs=mock(DrugService.class);
    final WxOrderLineMapper lines=mock(WxOrderLineMapper.class);
    final PrescriptionPurchaseService service=new PrescriptionPurchaseService(records,uses,stock,notifications,stores,drugs,lines);
    PhPrescRecordDO record; WxOrderDO order; PrescriptionUseDO use; DrugDO drug;
    @BeforeEach void setup() {
        TenantContextHolder.setTenantId(7L);
        var store=new StoreDO(); store.setStatus(1); when(stores.getStore(3L)).thenReturn(store);
        var item=new PrescItemVO();item.setDrugId(5L);item.setQty(2);
        record=new PhPrescRecordDO();record.setId(1L);record.setWxMemberId(2L);record.setStoreId(3L);
        record.setStatus(0);record.setReviewStatus(1);record.setApprovedUntil(LocalDateTime.now().plusHours(1));record.setApprovedItems(JsonUtils.toJsonString(List.of(item)));
        when(records.lock(1L)).thenReturn(record);
        order=new WxOrderDO();order.setId(4L);order.setMemberId(2L);order.setStoreId(3L);order.setPrescId(1L);order.setPayStatus(0);
        use=new PrescriptionUseDO();use.setPrescId(1L);use.setWxOrderId(4L);use.setStatus("HELD");use.setItemsSnapshot(record.getApprovedItems());
        when(uses.order(4L)).thenReturn(use);
        var line=new WxOrderLineDO();line.setDrugId(5L);line.setQty(2);when(lines.selectListByWxOrderId(4L)).thenReturn(List.of(line));
        drug=new DrugDO();drug.setStatus(1);drug.setApproveStatus(1);drug.setSaleableOnline(1);drug.setDrugType(0);drug.setIsRx(1);
        when(drugs.getDrug(5L)).thenReturn(drug);
    }
    @AfterEach void clear(){TenantContextHolder.clear();}
    @Test void ownershipReviewDeadlineAndStoreAreServerGates() {
        assertSame(record,service.lockApproved(1L,2L,3L));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.lockApproved(1L,9L,3L));
        record.setReviewStatus(0);assertThrows(RuntimeException.class,()->service.lockApproved(1L,2L,3L));
        record.setReviewStatus(1);record.setApprovedUntil(LocalDateTime.now().minusSeconds(1));assertThrows(RuntimeException.class,()->service.lockApproved(1L,2L,3L));
        when(stores.getStore(3L)).thenReturn(null);assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.lockApproved(1L,2L,3L));
    }
    @Test void cartMustMatchAndCannotCreateAnotherActiveUse() {
        var row=new WxCartDO();row.setDrugId(5L);row.setQty(3);
        assertThrows(RuntimeException.class,()->service.checkCart(record,List.of(row)));
        row.setQty(2);when(uses.active(1L)).thenReturn(use);assertThrows(RuntimeException.class,()->service.checkCart(record,List.of(row)));
        verifyNoInteractions(stock);
    }
    @Test void paymentRechecksRealLinesDrugStatusAndUseSnapshot() {
        assertDoesNotThrow(()->service.requirePayment(order));
        var changed=new WxOrderLineDO();changed.setDrugId(5L);changed.setQty(3);when(lines.selectListByWxOrderId(4L)).thenReturn(List.of(changed));
        assertThrows(RuntimeException.class,()->service.requirePayment(order));
        changed.setQty(2);drug.setDrugType(3);assertThrows(RuntimeException.class,()->service.requirePayment(order));
        drug.setDrugType(0);use.setItemsSnapshot("[]");assertThrows(RuntimeException.class,()->service.requirePayment(order));
        verify(uses,never()).update(any(PrescriptionUseDO.class),any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }
    @Test void paidUseCannotBeReleasedAndRepeatedReleaseDoesNotNotifyAgain() {
        order.setPayStatus(1);service.releaseUnpaid(order,"cancel");verifyNoInteractions(notifications);verify(uses,never()).update(any(PrescriptionUseDO.class),any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        order.setPayStatus(0);when(uses.update(any(PrescriptionUseDO.class),any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1,0);
        service.releaseUnpaid(order,"cancel");service.releaseUnpaid(order,"cancel");
        verify(notifications,times(1)).send(eq(2L),eq(1L),anyString());
        verify(uses,times(2)).update(argThat((PrescriptionUseDO row)->"SYSTEM".equals(row.getUpdater())),any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }
}
