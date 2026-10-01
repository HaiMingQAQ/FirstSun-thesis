package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.pharmacy.api.inventory.InventoryFacade;
import cn.iocoder.yudao.module.pharmacy.api.payment.PaymentFacade;
import cn.iocoder.yudao.module.pharmacy.controller.admin.member.vo.order.WxOrderSaveReqVO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.WxOrderDO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.WxOrderLineAllocDO;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.member.WxOrderLineAllocMapper;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.member.WxOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WxOrderPickupCodeTest {

    private static final Long ORDER_ID = 10L;
    private final WxOrderMapper orders = mock(WxOrderMapper.class);
    private final WxOrderLineAllocMapper allocations = mock(WxOrderLineAllocMapper.class);
    private final InventoryFacade inventory = mock(InventoryFacade.class);
    private final WxOrderServiceImpl service = new WxOrderServiceImpl();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "wxOrderMapper", orders);
        ReflectionTestUtils.setField(service, "wxOrderLineAllocMapper", allocations);
        ReflectionTestUtils.setField(service, "inventoryFacade", inventory);
        PaymentFacade payments = mock(PaymentFacade.class);
        when(payments.createPayOrder(any())).thenReturn("500");
        ReflectionTestUtils.setField(service, "paymentFacade", payments);
        when(orders.updateById(any(WxOrderDO.class))).thenReturn(1);
        ReflectionTestUtils.setField(service, "memberPointSettlementService", mock(MemberPointSettlementService.class));
        var paymentAccess = mock(WxOrderPaymentAccess.class);
        when(paymentAccess.lockOrder(anyLong())).thenAnswer(call -> orders.selectById((Long) call.getArgument(0)));
        ReflectionTestUtils.setField(service, "orderPaymentAccess", paymentAccess);
    }

    @Test
    void adminPickupCreateIgnoresClientCode() {
        WxOrderSaveReqVO request = createRequest(0);
        request.setPickupCode("KNOWN1");
        WxOrderDO created = captureCreatedOrder(request);

        assertEquals(0, created.getPayStatus());
        assertEquals(0, created.getStatus());
        assertPickupCode(created.getPickupCode());
        assertNotEquals("KNOWN1", created.getPickupCode());
    }

    @Test
    void adminPickupCreateWithoutClientCodeStillHasServerCode() {
        WxOrderDO created = captureCreatedOrder(createRequest(0));
        assertPickupCode(created.getPickupCode());
    }

    @Test
    void adminCreateRejectsUnsupportedOrderType() {
        assertThrows(ServiceException.class, () -> service.createWxOrder(createRequest(2)));
        verify(orders, never()).insert(any(WxOrderDO.class));
    }

    @Test
    void adminCreateUsesDatabaseAssignedId() {
        WxOrderSaveReqVO request = createRequest(0);
        request.setId(999L);
        when(orders.insert(any(WxOrderDO.class))).thenAnswer(invocation -> {
            WxOrderDO order = invocation.getArgument(0);
            assertNull(order.getId());
            order.setId(ORDER_ID);
            return 1;
        });

        assertEquals(ORDER_ID, service.createWxOrder(request));
    }

    @Test
    void adminDeliveryCreateDiscardsClientCode() {
        WxOrderSaveReqVO request = createRequest(1);
        request.setPickupCode("KNOWN1");
        assertNull(captureCreatedOrder(request).getPickupCode());
    }

    @Test
    void createRetriesUniqueIndexCollision() {
        List<String> attemptedCodes = new ArrayList<>();
        when(orders.insert(any(WxOrderDO.class))).thenAnswer(invocation -> {
            WxOrderDO order = invocation.getArgument(0);
            attemptedCodes.add(order.getPickupCode());
            if (attemptedCodes.size() == 1) {
                throw new DuplicateKeyException("Duplicate entry for key 'uk_pickup_code'");
            }
            order.setId(ORDER_ID);
            return 1;
        });

        assertEquals(ORDER_ID, service.createWxOrder(createRequest(0)));
        assertEquals(2, attemptedCodes.size());
        assertPickupCode(attemptedCodes.get(0));
        assertPickupCode(attemptedCodes.get(1));
    }

    @Test
    void repeatedUniqueIndexCollisionsStopWithinBound() {
        AtomicInteger attempts = new AtomicInteger();
        when(orders.insert(any(WxOrderDO.class))).thenAnswer(invocation -> {
            attempts.incrementAndGet();
            throw new DuplicateKeyException("Duplicate entry for key 'uk_pickup_code'");
        });

        assertThrows(DuplicateKeyException.class, () -> service.createWxOrder(createRequest(0)));
        assertTrue(attempts.get() > 1);
        assertTrue(attempts.get() <= 10, "collision retry must be bounded");
    }

    @Test
    void paidPickupFlowsThroughPickingAndVerificationWithServerCode() {
        AtomicReference<WxOrderDO> persisted = new AtomicReference<>();
        when(orders.insert(any(WxOrderDO.class))).thenAnswer(invocation -> {
            WxOrderDO order = invocation.getArgument(0);
            order.setId(ORDER_ID);
            persisted.set(order);
            return 1;
        });
        when(orders.selectById(ORDER_ID)).thenAnswer(invocation -> persisted.get());
        when(orders.update(any(), any())).thenAnswer(invocation -> {
            WxOrderDO changes = invocation.getArgument(0);
            WxOrderDO order = persisted.get();
            if (changes.getStatus() != null) order.setStatus(changes.getStatus());
            if (changes.getPayStatus() != null) order.setPayStatus(changes.getPayStatus());
            if (changes.getPickupCode() != null) order.setPickupCode(changes.getPickupCode());
            if (changes.getVerifyBy() != null) order.setVerifyBy(changes.getVerifyBy());
            return 1;
        });
        WxOrderLineAllocDO frozen = new WxOrderLineAllocDO();
        frozen.setId(80L);
        frozen.setStatus(WxOrderLineAllocDO.STATUS_FROZEN);
        frozen.setOrderNo("WX-10");
        frozen.setWxOrderLineId(81L);
        frozen.setDrugId(82L);
        frozen.setBatchId(83L);
        frozen.setLocationId(84L);
        frozen.setQty(1);
        when(allocations.selectListByWxOrderId(ORDER_ID)).thenReturn(List.of(frozen));

        WxOrderSaveReqVO request = createRequest(0);
        request.setPickupCode("KNOWN1");
        service.createWxOrder(request);
        String code = persisted.get().getPickupCode();
        assertPickupCode(code);
        assertThrows(ServiceException.class, () -> service.verifyWxOrder(ORDER_ID, code, 101L));
        assertEquals(0, persisted.get().getStatus());

        service.payWxOrder(ORDER_ID, "PAY-10");
        assertEquals(1, persisted.get().getPayStatus());
        assertEquals(1, persisted.get().getStatus());
        service.startPicking(ORDER_ID);
        assertEquals(2, persisted.get().getStatus());
        service.finishPicking(ORDER_ID);
        assertEquals(3, persisted.get().getStatus());
        assertEquals(code, persisted.get().getPickupCode());
        assertThrows(ServiceException.class, () -> service.verifyWxOrder(ORDER_ID, "KNOWN1", 101L));
        service.verifyWxOrder(ORDER_ID, code, 101L);
        assertEquals(4, persisted.get().getStatus());
        assertEquals(101L, persisted.get().getVerifyBy());
        verify(inventory).consumeReservation(eq(1L), any());
    }

    @Test
    void finishingHistoricalPickupWithoutCodeAddsOne() {
        WxOrderDO order = pickingOrder(0, null);
        when(orders.selectById(ORDER_ID)).thenReturn(order);
        when(orders.update(any(), any())).thenReturn(1);

        service.finishPicking(ORDER_ID);

        var captor = org.mockito.ArgumentCaptor.forClass(WxOrderDO.class);
        verify(orders).update(captor.capture(), any());
        assertEquals(3, captor.getValue().getStatus());
        assertPickupCode(captor.getValue().getPickupCode());
    }

    @Test
    void unpaidPickupCannotFinishPicking() {
        WxOrderDO order = pickingOrder(0, "ABC123");
        order.setPayStatus(0);
        when(orders.selectById(ORDER_ID)).thenReturn(order);

        assertThrows(ServiceException.class, () -> service.finishPicking(ORDER_ID));

        assertEquals(2, order.getStatus());
        verify(orders, never()).update(any(), any());
    }

    @Test
    void unpaidHistoricalPickupCannotAcquireCodeAtFinishPicking() {
        WxOrderDO order = pickingOrder(0, null);
        order.setPayStatus(0);
        when(orders.selectById(ORDER_ID)).thenReturn(order);

        assertThrows(ServiceException.class, () -> service.finishPicking(ORDER_ID));

        assertEquals(2, order.getStatus());
        assertNull(order.getPickupCode());
        verify(orders, never()).update(any(), any());
    }

    @Test
    void finishPickingRejectsLostConditionalUpdate() {
        WxOrderDO order = pickingOrder(0, "ABC123");
        when(orders.selectById(ORDER_ID)).thenReturn(order);
        when(orders.update(any(), any())).thenReturn(0);

        assertThrows(ServiceException.class, () -> service.finishPicking(ORDER_ID));

        assertEquals(2, order.getStatus());
        verify(orders, times(1)).update(any(), any());
    }

    @Test
    void finishPickingWithMissingCodeRejectsLostConditionalUpdate() {
        WxOrderDO order = pickingOrder(0, null);
        when(orders.selectById(ORDER_ID)).thenReturn(order);
        when(orders.update(any(), any())).thenReturn(0);

        assertThrows(ServiceException.class, () -> service.finishPicking(ORDER_ID));

        assertEquals(2, order.getStatus());
        assertNull(order.getPickupCode());
        verify(orders, times(1)).update(any(), any());
    }

    @Test
    void finishingHistoricalPickupRetriesUniqueIndexCollision() {
        WxOrderDO order = pickingOrder(0, "  ");
        when(orders.selectById(ORDER_ID)).thenReturn(order);
        List<String> attemptedCodes = new ArrayList<>();
        when(orders.update(any(), any())).thenAnswer(invocation -> {
            WxOrderDO changes = invocation.getArgument(0);
            attemptedCodes.add(changes.getPickupCode());
            if (attemptedCodes.size() == 1) {
                throw new DuplicateKeyException("Duplicate entry for key 'uk_pickup_code'");
            }
            return 1;
        });

        service.finishPicking(ORDER_ID);
        assertEquals(2, attemptedCodes.size());
        assertPickupCode(attemptedCodes.get(0));
        assertPickupCode(attemptedCodes.get(1));
    }

    @Test
    void finishingDeliveryDoesNotCreateCodeAndDeliveryCannotBeVerified() {
        WxOrderDO order = pickingOrder(1, null);
        when(orders.selectById(ORDER_ID)).thenReturn(order);
        when(orders.update(any(), any())).thenReturn(1);

        service.finishPicking(ORDER_ID);
        var captor = org.mockito.ArgumentCaptor.forClass(WxOrderDO.class);
        verify(orders).update(captor.capture(), any());
        assertEquals(3, captor.getValue().getStatus());
        assertNull(captor.getValue().getPickupCode());

        order.setStatus(3);
        order.setPickupCode("KNOWN1");
        assertThrows(ServiceException.class, () -> service.verifyWxOrder(ORDER_ID, "KNOWN1", 101L));
        verify(orders, times(1)).update(any(), any());
    }

    @Test
    void finishingDeliveryRejectsLostConditionalUpdateWithoutCreatingCode() {
        WxOrderDO order = pickingOrder(1, null);
        when(orders.selectById(ORDER_ID)).thenReturn(order);
        when(orders.update(any(), any())).thenReturn(0);

        assertThrows(ServiceException.class, () -> service.finishPicking(ORDER_ID));

        var captor = org.mockito.ArgumentCaptor.forClass(WxOrderDO.class);
        verify(orders).update(captor.capture(), any());
        assertNull(captor.getValue().getPickupCode());
        assertEquals(2, order.getStatus());
    }

    @Test
    void unpaidPickupAtWaitVerifyCannotBeVerified() {
        WxOrderDO order = pickingOrder(0, "ABC123");
        order.setStatus(3);
        order.setPayStatus(0);
        when(orders.selectById(ORDER_ID)).thenReturn(order);

        assertThrows(ServiceException.class, () -> service.verifyWxOrder(ORDER_ID, "ABC123", 101L));
        verify(orders, never()).update(any(), any());
    }

    @Test
    void blankHistoricalPickupCodeCannotBeVerifiedWithBlankInput() {
        WxOrderDO order = pickingOrder(0, "   ");
        order.setStatus(3);
        when(orders.selectById(ORDER_ID)).thenReturn(order);

        assertThrows(ServiceException.class, () -> service.verifyWxOrder(ORDER_ID, "   ", 101L));
        verify(orders, never()).update(any(), any());
    }

    private WxOrderDO captureCreatedOrder(WxOrderSaveReqVO request) {
        when(orders.insert(any(WxOrderDO.class))).thenAnswer(invocation -> {
            WxOrderDO order = invocation.getArgument(0);
            order.setId(ORDER_ID);
            return 1;
        });
        assertEquals(ORDER_ID, service.createWxOrder(request));
        var captor = org.mockito.ArgumentCaptor.forClass(WxOrderDO.class);
        verify(orders).insert(captor.capture());
        return captor.getValue();
    }

    private WxOrderSaveReqVO createRequest(int orderType) {
        WxOrderSaveReqVO request = new WxOrderSaveReqVO();
        request.setOrderNo("WX-10");
        request.setMemberId(1L);
        request.setStoreId(1L);
        request.setOrderType(orderType);
        request.setGoodsAmount(new BigDecimal("25.00"));
        request.setPayableAmount(new BigDecimal("25.00"));
        request.setExpireAt(LocalDateTime.now().plusMinutes(30));
        return request;
    }

    private WxOrderDO pickingOrder(int orderType, String code) {
        WxOrderDO order = new WxOrderDO();
        order.setId(ORDER_ID);
        order.setOrderNo("WX-10");
        order.setStoreId(1L);
        order.setOrderType(orderType);
        order.setPayStatus(1);
        order.setStatus(2);
        order.setPickupCode(code);
        return order;
    }

    private void assertPickupCode(String code) {
        assertNotNull(code);
        assertTrue(code.matches("[A-HJ-NP-Z2-9]{6}"), "pickup code must use the server's six-character alphabet");
    }
}
