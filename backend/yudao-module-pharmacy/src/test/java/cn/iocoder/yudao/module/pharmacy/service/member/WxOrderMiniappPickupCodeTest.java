package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.api.DrugApi;
import cn.iocoder.yudao.module.pharmacy.api.dto.DrugRespDTO;
import cn.iocoder.yudao.module.pharmacy.api.member.dto.SalePointCalcDTO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.MemberAddressDO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.WxCartDO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.WxOrderDO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.WxOrderLineDO;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.member.WxOrderLineMapper;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.member.WxOrderMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Miniapp pickup code retry checks. Transaction rollback is covered separately with a real mapper. */
@ExtendWith(MockitoExtension.class)
class WxOrderMiniappPickupCodeTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long STORE_ID = 10L;

    @Mock private WxOrderMapper orders;
    @Mock private WxOrderLineMapper lines;
    @Mock private WxCartService carts;
    @Mock private DrugApi drugs;
    @Mock private MemberPointSettlementService points;
    @Mock private MemberAddressService addresses;
    @InjectMocks private WxOrderServiceImpl service;

    @BeforeAll
    static void metadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        for (Class<?> type : List.of(WxOrderDO.class, WxOrderLineDO.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), type);
        }
    }

    @BeforeEach
    void setUp() {
        LoginUser login = new LoginUser();
        login.setId(MEMBER_ID);
        login.setTenantId(7L);
        login.setUserType(UserTypeEnum.MEMBER.getValue());
        TenantContextHolder.setTenantId(7L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(login, null, List.of()));

        WxCartDO cart = new WxCartDO();
        cart.setId(20L);
        cart.setMemberId(MEMBER_ID);
        cart.setStoreId(STORE_ID);
        cart.setDrugId(30L);
        cart.setQty(1);
        when(carts.getSelectedCartListByMemberId(MEMBER_ID)).thenReturn(List.of(cart));
        DrugRespDTO drug = new DrugRespDTO();
        drug.setId(30L);
        drug.setStatus(1);
        drug.setApproveStatus(1);
        drug.setSaleableOnline(1);
        drug.setIsRx(0);
        drug.setRetailPrice(new BigDecimal("25.00"));
        when(drugs.getDrugList(List.of(30L))).thenReturn(List.of(drug));
        when(points.calcSalePoints(eq(MEMBER_ID), any(), eq(0)))
                .thenReturn(new SalePointCalcDTO(0, BigDecimal.ZERO, 25, 5000, 5000));
        when(orders.selectCountByOrderNoPrefix(anyString())).thenReturn(0L);
    }

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void firstPickupCodeCollisionIsRetriedBeforeWritingLines() {
        AtomicInteger attempts = new AtomicInteger();
        List<String> attemptedCodes = new ArrayList<>();
        when(orders.insert(any(WxOrderDO.class))).thenAnswer(invocation -> {
            WxOrderDO order = invocation.getArgument(0);
            attemptedCodes.add(order.getPickupCode());
            if (attempts.incrementAndGet() == 1) {
                throw pickupCodeCollision();
            }
            order.setId(100L);
            return 1;
        });

        assertEquals(100L, service.createOrderFromCart(MEMBER_ID, STORE_ID, 0, null, null, null, 0));

        assertEquals(2, attempts.get());
        assertEquals(2, attemptedCodes.size());
        attemptedCodes.forEach(code -> assertTrue(code.matches("[A-HJ-NP-Z2-9]{6}")));
        verify(lines).insert(argThat((WxOrderLineDO line) -> Long.valueOf(100L).equals(line.getWxOrderId())));
        verify(points).deductSalePoints(eq(MEMBER_ID), anyString(), eq(0));
        verify(carts).deleteWxCart(20L);
    }

    @Test
    void repeatedPickupCodeCollisionIsBoundedAndDoesNotAdvanceOrderWork() {
        when(orders.insert(any(WxOrderDO.class))).thenThrow(pickupCodeCollision());

        assertThrows(DuplicateKeyException.class,
                () -> service.createOrderFromCart(MEMBER_ID, STORE_ID, 0, null, null, null, 0));

        verify(orders, times(5)).insert(any(WxOrderDO.class));
        verifyNoInteractions(lines);
        verify(points, never()).deductSalePoints(anyLong(), anyString(), anyInt());
        verify(carts, never()).deleteWxCart(anyLong());
    }

    @Test
    void unrelatedUniqueKeyFailureIsNotRetried() {
        DuplicateKeyException unrelated = new DuplicateKeyException(
                "INSERT INTO ph_wx_order (pickup_code, order_no) failed",
                new SQLIntegrityConstraintViolationException(
                        "Duplicate entry 'WX-10' for key 'uk_order_no'", "23000", 1062));
        when(orders.insert(any(WxOrderDO.class))).thenThrow(unrelated);

        assertSame(unrelated, assertThrows(DuplicateKeyException.class,
                () -> service.createOrderFromCart(MEMBER_ID, STORE_ID, 0, null, null, null, 0)));

        verify(orders).insert(any(WxOrderDO.class));
        verifyNoInteractions(lines);
        verify(points, never()).deductSalePoints(anyLong(), anyString(), anyInt());
        verify(carts, never()).deleteWxCart(anyLong());
    }

    @Test
    void deliveryDoesNotGeneratePickupCode() {
        MemberAddressDO address = new MemberAddressDO();
        address.setUserId(MEMBER_ID);
        address.setName("收件人");
        address.setMobile("13800000000");
        address.setDetailAddress("测试地址");
        when(addresses.validateMemberAddressExists(40L)).thenReturn(address);
        when(orders.insert(any(WxOrderDO.class))).thenAnswer(invocation -> {
            WxOrderDO order = invocation.getArgument(0);
            order.setId(101L);
            return 1;
        });

        assertEquals(101L, service.createOrderFromCart(MEMBER_ID, STORE_ID, 1, 40L, null, null, 0));

        verify(orders).insert(argThat((WxOrderDO order) -> order.getPickupCode() == null && order.getOrderType() == 1));
        verify(lines).insert(any(WxOrderLineDO.class));
    }

    private DuplicateKeyException pickupCodeCollision() {
        return new DuplicateKeyException("duplicate pickup code",
                new SQLIntegrityConstraintViolationException(
                        "Duplicate entry 'ABC123' for key 'uk_pickup_code'", "23000", 1062));
    }
}
