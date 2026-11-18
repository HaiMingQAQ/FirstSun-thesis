package cn.iocoder.yudao.module.pharmacy.controller.app.member;

import cn.iocoder.yudao.module.pharmacy.dal.dataobject.base.DrugDO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.WxOrderLineDO;
import cn.iocoder.yudao.module.pharmacy.service.base.DrugService;
import cn.iocoder.yudao.module.pharmacy.service.member.WxOrderLineService;
import cn.iocoder.yudao.module.pharmacy.service.member.WxOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AppWxOrderImagesTest {
    private final WxOrderService orders = mock(WxOrderService.class);
    private final WxOrderLineService lines = mock(WxOrderLineService.class);
    private final DrugService drugs = mock(DrugService.class);
    private final AppWxOrderController controller = new AppWxOrderController();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(controller, "wxOrderService", orders);
        ReflectionTestUtils.setField(controller, "wxOrderLineService", lines);
        ReflectionTestUtils.setField(controller, "drugService", drugs);
    }

    @Test
    void offlineProductImageIsReturnedWithOriginalOrderFields() {
        var line = new WxOrderLineDO();
        line.setDrugId(7L);
        line.setDrugName("下单名称");
        line.setPrice(new BigDecimal("19.50"));
        when(lines.getWxOrderLineListByWxOrderId(1L)).thenReturn(List.of(line));
        var drug = new DrugDO();
        drug.setId(7L);
        drug.setIsRx(0);
        drug.setApproveStatus(1);
        drug.setStatus(0);
        drug.setSaleableOnline(0);
        drug.setImageUrl("/images/7.png");
        when(drugs.getDrugList(List.of(7L))).thenReturn(List.of(drug));

        var result = controller.getOrderLineList(1L).getData().get(0);
        assertEquals("/images/7.png", result.getCatalogImageUrl());
        assertEquals("下单名称", result.getDrugName());
        assertEquals(line.getPrice(), result.getPrice());
        verify(orders).validateWxOrderOwner(isNull(), eq(1L));
    }

    @Test
    void missingRxAndUnapprovedImagesAreExcluded() {
        var rows = List.of(7L, 8L, 9L).stream().map(id -> {
            var line = new WxOrderLineDO();
            line.setDrugId(id);
            return line;
        }).toList();
        var rx = new DrugDO();
        rx.setId(7L);
        rx.setIsRx(1);
        rx.setApproveStatus(1);
        rx.setImageUrl("/private/rx.png");
        var unapproved = new DrugDO();
        unapproved.setId(8L);
        unapproved.setIsRx(0);
        unapproved.setApproveStatus(0);
        unapproved.setImageUrl("/images/unapproved.png");
        when(lines.getWxOrderLineListByWxOrderId(1L)).thenReturn(rows);
        when(drugs.getDrugList(anyList())).thenReturn(List.of(rx, unapproved));
        assertTrue(controller.getOrderLineList(1L).getData().stream()
                .allMatch(line -> "".equals(line.getCatalogImageUrl())));
    }

    @Test
    void ownershipFailurePreventsImageLookup() {
        doThrow(new IllegalArgumentException("订单不属于当前会员"))
                .when(orders).validateWxOrderOwner(isNull(), eq(1L));
        assertThrows(IllegalArgumentException.class, () -> controller.getOrderLineList(1L));
        verifyNoInteractions(lines, drugs);
    }
}
