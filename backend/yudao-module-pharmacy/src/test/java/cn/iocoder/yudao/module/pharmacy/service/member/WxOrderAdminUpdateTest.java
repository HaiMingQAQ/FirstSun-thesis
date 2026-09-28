package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.iocoder.yudao.module.pharmacy.api.payment.PaymentFacade;
import cn.iocoder.yudao.module.pharmacy.api.payment.dto.PayOrderDTO;
import cn.iocoder.yudao.module.pharmacy.controller.admin.member.vo.order.WxOrderSaveReqVO;
import cn.iocoder.yudao.module.pharmacy.controller.admin.member.vo.order.WxOrderUpdateReqVO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.WxOrderDO;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.member.WxOrderMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WxOrderAdminUpdateTest {

    private final WxOrderMapper mapper = mock(WxOrderMapper.class);
    private final PaymentFacade payments = mock(PaymentFacade.class);
    private final WxOrderServiceImpl service = new WxOrderServiceImpl();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "wxOrderMapper", mapper);
        ReflectionTestUtils.setField(service, "paymentFacade", payments);
        when(payments.createPayOrder(any())).thenReturn("500");
        when(mapper.updateById(any(WxOrderDO.class))).thenReturn(1);
        WxOrderDO existing = new WxOrderDO();
        existing.setId(10L);
        when(mapper.selectById(10L)).thenReturn(existing);
    }

    @Test
    void updatesRemarkOnly() throws Exception {
        WxOrderUpdateReqVO request = new WxOrderUpdateReqVO();
        request.setId(10L);
        request.setRemark("联系顾客后再发货");

        service.updateWxOrder(request);

        WxOrderDO update = captureUpdate();
        assertEquals(10L, update.getId());
        assertEquals("联系顾客后再发货", update.getRemark());
        assertOnlyRemarkIsUpdated(update);
    }

    @Test
    void ignoresForgedPaymentStateAndVerificationFields() throws Exception {
        String payload = """
                {"id":10,"remark":"订单备注","orderNo":"forged","memberId":2,"storeId":22,
                 "orderType":1,"goodsAmount":1,"payableAmount":1,"payNo":"forged",
                 "payStatus":1,"paidAt":"2026-01-01T00:00:00","payOrderId":999,
                 "status":4,"cancelReason":"forged","finishAt":"2026-01-01T00:00:00",
                 "pickupCode":"forged","verifyBy":888,"verifyAt":"2026-01-01T00:00:00",
                 "expireAt":"2026-01-01T00:00:00","addressSnapshot":"forged"}
                """;
        WxOrderUpdateReqVO request = new ObjectMapper().readValue(payload, WxOrderUpdateReqVO.class);

        service.updateWxOrder(request);

        WxOrderDO update = captureUpdate();
        assertEquals(10L, update.getId());
        assertEquals("订单备注", update.getRemark());
        assertOnlyRemarkIsUpdated(update);
        verify(mapper, never()).selectByOrderNo(any());
    }

    @Test
    void forgedCompletedOrderIsCreatedAsUnpaidAndUnverified() {
        WxOrderSaveReqVO request = validCreateRequest();
        request.setPayStatus(1);
        request.setStatus(4);
        request.setPayNo("forged-payment");
        request.setPaidAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        request.setPayOrderId(999L);
        request.setCancelReason("forged-cancel");
        request.setFinishAt(LocalDateTime.of(2026, 1, 2, 0, 0));
        request.setVerifyBy(888L);
        request.setVerifyAt(LocalDateTime.of(2026, 1, 3, 0, 0));

        WxOrderDO created = createAndCapture(request);

        assertEquals(0, created.getPayStatus());
        assertEquals(0, created.getStatus());
        assertNull(created.getPayNo());
        assertNull(created.getPaidAt());
        assertNull(created.getPayOrderId());
        assertNull(created.getCancelReason());
        assertNull(created.getFinishAt());
        assertNull(created.getVerifyBy());
        assertNull(created.getVerifyAt());
        verify(payments).createPayOrder(argThat((PayOrderDTO pay) ->
                "WX-10".equals(pay.getBizNo()) && pay.getPriceFen() == 2500));
    }

    @Test
    void legitimateCreateStillUsesThePaymentFlow() {
        WxOrderSaveReqVO request = validCreateRequest();
        request.setRemark("新订单备注");

        WxOrderDO created = createAndCapture(request);

        assertEquals(10L, created.getId());
        assertEquals(0, created.getPayStatus());
        assertEquals(0, created.getStatus());
        assertEquals(new BigDecimal("25.00"), created.getPayableAmount());
        assertEquals("新订单备注", created.getRemark());
        verify(payments).createPayOrder(argThat((PayOrderDTO pay) ->
                "WX-10".equals(pay.getBizNo()) && pay.getPriceFen() == 2500));
    }

    @Test
    void createRequestDoesNotRequireClientPaymentOrOrderState() {
        WxOrderSaveReqVO request = validCreateRequest();
        assertNull(request.getPayStatus());
        assertNull(request.getStatus());

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(request).isEmpty());
        }
    }

    @Test
    void forgedInvalidStatusIsIgnoredAtCreation() {
        WxOrderSaveReqVO request = validCreateRequest();
        request.setPayStatus(2);
        request.setStatus(99);

        WxOrderDO created = createAndCapture(request);

        assertEquals(0, created.getPayStatus());
        assertEquals(0, created.getStatus());
    }

    private WxOrderSaveReqVO validCreateRequest() {
        WxOrderSaveReqVO request = new WxOrderSaveReqVO();
        request.setOrderNo("WX-10");
        request.setMemberId(1L);
        request.setStoreId(1L);
        request.setOrderType(0);
        request.setGoodsAmount(new BigDecimal("25.00"));
        request.setPayableAmount(new BigDecimal("25.00"));
        request.setExpireAt(LocalDateTime.now().plusMinutes(30));
        return request;
    }

    private WxOrderDO createAndCapture(WxOrderSaveReqVO request) {
        when(mapper.insert(any(WxOrderDO.class))).thenAnswer(invocation -> {
            WxOrderDO order = invocation.getArgument(0);
            order.setId(10L);
            return 1;
        });
        assertEquals(10L, service.createWxOrder(request));
        var captor = org.mockito.ArgumentCaptor.forClass(WxOrderDO.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }

    private WxOrderDO captureUpdate() {
        var captor = org.mockito.ArgumentCaptor.forClass(WxOrderDO.class);
        verify(mapper).updateById(captor.capture());
        return captor.getValue();
    }

    private void assertOnlyRemarkIsUpdated(WxOrderDO update) throws IllegalAccessException {
        for (Field field : WxOrderDO.class.getDeclaredFields()) {
            if (field.getName().equals("id") || field.getName().equals("remark")) {
                continue;
            }
            field.setAccessible(true);
            assertNull(field.get(update), field.getName() + " must not be changed by generic update");
        }
    }

}
