package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.controller.admin.member.vo.order.WxOrderPageReqVO;
import cn.iocoder.yudao.module.pharmacy.controller.admin.member.vo.order.WxOrderSaveReqVO;
import cn.iocoder.yudao.module.pharmacy.controller.admin.member.vo.order.WxOrderUpdateReqVO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.base.EmployeeDO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.base.StoreDO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.WxOrderDO;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.member.WxOrderMapper;
import cn.iocoder.yudao.module.pharmacy.service.base.EmployeeService;
import cn.iocoder.yudao.module.pharmacy.service.base.StoreService;
import cn.iocoder.yudao.module.pharmacy.service.permission.PharmacyStoreDataAccess;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 合成租户、员工和门店数据验证管理端门禁；数据库租户 SQL 由框架拦截器负责。 */
class AdminWxOrderServiceTest {

    private static final long TENANT_1 = 100L;
    private static final long TENANT_2 = 200L;
    private static final long STAFF_A = 1001L;
    private static final long STAFF_B = 1002L;
    private static final long TENANT_ADMIN = 1003L;
    private static final long UNBOUND = 1004L;
    private static final long EMPLOYEE_A = 501L;
    private static final long EMPLOYEE_B = 502L;
    private static final long STORE_A = 11L;
    private static final long STORE_B = 12L;
    private static final long OTHER_TENANT_STORE = 21L;
    private static final long ORDER_A = 101L;
    private static final long ORDER_B = 102L;
    private static final long OTHER_TENANT_ORDER = 201L;

    private final PermissionApi permissions = mock(PermissionApi.class);
    private final EmployeeService employees = mock(EmployeeService.class);
    private final StoreService stores = mock(StoreService.class);
    private final WxOrderService orders = mock(WxOrderService.class);
    private final WxOrderLineService lines = mock(WxOrderLineService.class);
    private final WxOrderMapper mapper = mock(WxOrderMapper.class);
    private final AdminWxOrderService adminOrders = new AdminWxOrderService(
            new PharmacyStoreDataAccess(permissions, employees, stores), orders, lines, mapper, employees);

    private LoginUser login;
    private WxOrderDO orderA;
    private WxOrderDO orderB;

    @BeforeEach
    void setUp() {
        authenticate(STAFF_A, TENANT_1);
        when(permissions.hasAnyRoles(TENANT_ADMIN, "tenant_admin")).thenReturn(true);
        when(employees.getEmployeeByUserId(STAFF_A)).thenReturn(employee(EMPLOYEE_A, STAFF_A, TENANT_1, STORE_A));
        when(employees.getEmployeeByUserId(STAFF_B)).thenReturn(employee(EMPLOYEE_B, STAFF_B, TENANT_1, STORE_B));
        when(stores.getStore(STORE_A)).thenReturn(store(STORE_A));
        when(stores.getStore(STORE_B)).thenReturn(store(STORE_B));
        orderA = order(ORDER_A, STORE_A);
        orderB = order(ORDER_B, STORE_B);
        when(orders.getWxOrder(ORDER_A)).thenReturn(orderA);
        when(orders.getWxOrder(ORDER_B)).thenReturn(orderB);
    }

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void staffListsAreFilteredInSqlToOwnStore() {
        WxOrderPageReqVO request = new WxOrderPageReqVO();
        PageResult<WxOrderDO> pageA = new PageResult<>(List.of(orderA), 1L);
        when(mapper.selectAdminPage(same(request), eq(STORE_A))).thenReturn(pageA);
        assertSame(pageA, adminOrders.getWxOrderPage(request));
        verify(mapper).selectAdminPage(request, STORE_A);

        authenticate(STAFF_B, TENANT_1);
        adminOrders.getWxOrderPage(request);
        verify(mapper).selectAdminPage(request, STORE_B);

        request.setStoreId(STORE_A);
        assertThrows(AccessDeniedException.class, () -> adminOrders.getWxOrderPage(request));
    }

    @Test
    void guessedOtherStoreOrderAndLinesAreDenied() {
        assertThrows(AccessDeniedException.class, () -> adminOrders.getWxOrder(ORDER_B));
        assertThrows(AccessDeniedException.class, () -> adminOrders.getWxOrderLineList(ORDER_B));
        verify(lines, never()).getWxOrderLineListByWxOrderId(ORDER_B);
        assertSame(orderA, adminOrders.getWxOrder(ORDER_A));
    }

    @Test
    void submittedOtherStoreIsDeniedForCreateAndExistingOrderUpdate() {
        WxOrderSaveReqVO request = new WxOrderSaveReqVO();
        request.setStoreId(STORE_B);
        assertThrows(AccessDeniedException.class, () -> adminOrders.createWxOrder(request));

        WxOrderUpdateReqVO update = new WxOrderUpdateReqVO();
        update.setId(ORDER_B);
        assertThrows(AccessDeniedException.class, () -> adminOrders.updateWxOrder(update));
        verify(orders, never()).createWxOrder(request);
        verify(orders, never()).updateWxOrder(update);
    }

    @Test
    void allManagementWritesRejectOtherStoreBeforeBusinessActions() {
        assertThrows(AccessDeniedException.class, () -> adminOrders.deleteWxOrder(ORDER_B));
        assertThrows(AccessDeniedException.class, () -> adminOrders.payWxOrder(ORDER_B, "pay"));
        assertThrows(AccessDeniedException.class, () -> adminOrders.cancelWxOrder(ORDER_B, "cancel"));
        assertThrows(AccessDeniedException.class, () -> adminOrders.refundWxOrder(ORDER_B, "refund"));
        assertThrows(AccessDeniedException.class, () -> adminOrders.reserveWxOrder(ORDER_B));
        assertThrows(AccessDeniedException.class, () -> adminOrders.startPicking(ORDER_B));
        assertThrows(AccessDeniedException.class, () -> adminOrders.finishPicking(ORDER_B));
        assertThrows(AccessDeniedException.class, () -> adminOrders.verifyWxOrder(ORDER_B, "code"));
        assertThrows(AccessDeniedException.class, () -> adminOrders.closeExpiredWxOrders(STORE_B, 10));
        assertThrows(AccessDeniedException.class,
                () -> adminOrders.releaseFrozenStockOfClosedOrders(STORE_B, 10));
        verify(orders, never()).deleteWxOrder(ORDER_B);
        verify(orders, never()).payWxOrder(ORDER_B, "pay");
        verify(orders, never()).cancelWxOrder(ORDER_B, "cancel");
        verify(orders, never()).refundWxOrder(ORDER_B, "refund");
        verify(orders, never()).reserveWxOrder(ORDER_B);
        verify(orders, never()).startPicking(ORDER_B);
        verify(orders, never()).finishPicking(ORDER_B);
        verify(orders, never()).verifyWxOrder(eq(ORDER_B), eq("code"), anyLong());
        verify(orders, never()).closeExpiredWxOrders(STORE_B, 10);
        verify(orders, never()).releaseFrozenStockOfClosedOrders(STORE_B, 10);
    }

    @Test
    void unboundUserHasNoStoreBusinessData() {
        authenticate(UNBOUND, TENANT_1);
        assertThrows(AccessDeniedException.class, () -> adminOrders.getWxOrderPage(new WxOrderPageReqVO()));
        assertThrows(AccessDeniedException.class, () -> adminOrders.getWxOrder(ORDER_A));
        assertThrows(AccessDeniedException.class, () -> adminOrders.closeExpiredWxOrders(STORE_A, 10));
        assertThrows(AccessDeniedException.class, () -> adminOrders.verifyWxOrder(ORDER_A, "code"));
    }

    @Test
    void verifyUsesAuthenticatedEmployeeId() {
        adminOrders.verifyWxOrder(ORDER_A, "code");
        verify(orders).verifyWxOrder(ORDER_A, "code", EMPLOYEE_A);
    }

    @Test
    void verifyRejectsForeignStoreAndTenantEmployeeEvenWithAdminRole() {
        authenticate(TENANT_ADMIN, TENANT_1);
        when(employees.getEmployeeByUserId(TENANT_ADMIN))
                .thenReturn(employee(EMPLOYEE_B, TENANT_ADMIN, TENANT_1, STORE_B));
        assertThrows(AccessDeniedException.class, () -> adminOrders.verifyWxOrder(ORDER_A, "code"));

        when(employees.getEmployeeByUserId(TENANT_ADMIN))
                .thenReturn(employee(EMPLOYEE_B, TENANT_ADMIN, TENANT_2, STORE_A));
        assertThrows(AccessDeniedException.class, () -> adminOrders.verifyWxOrder(ORDER_A, "code"));
        verify(orders, never()).verifyWxOrder(eq(ORDER_A), eq("code"), anyLong());
    }

    @Test
    void verifyRejectsUnboundOrInactiveAdmin() {
        authenticate(TENANT_ADMIN, TENANT_1);
        assertThrows(AccessDeniedException.class, () -> adminOrders.verifyWxOrder(ORDER_A, "code"));

        EmployeeDO inactive = employee(EMPLOYEE_A, TENANT_ADMIN, TENANT_1, STORE_A);
        inactive.setStatus(0);
        when(employees.getEmployeeByUserId(TENANT_ADMIN)).thenReturn(inactive);
        assertThrows(AccessDeniedException.class, () -> adminOrders.verifyWxOrder(ORDER_A, "code"));
        verify(orders, never()).verifyWxOrder(eq(ORDER_A), eq("code"), anyLong());
    }

    @Test
    void tenantAdminCanAccessBothOwnTenantStoresWithoutEmployeeBinding() {
        authenticate(TENANT_ADMIN, TENANT_1);
        WxOrderPageReqVO request = new WxOrderPageReqVO();
        adminOrders.getWxOrderPage(request);
        verify(mapper).selectAdminPage(same(request), isNull());
        assertSame(orderB, adminOrders.getWxOrder(ORDER_B));
        adminOrders.startPicking(ORDER_B);
        verify(orders).startPicking(ORDER_B);
        adminOrders.closeExpiredWxOrders(STORE_B, 10);
        verify(orders).closeExpiredWxOrders(STORE_B, 10);

        WxOrderSaveReqVO create = new WxOrderSaveReqVO();
        create.setStoreId(STORE_B);
        adminOrders.createWxOrder(create);
        verify(orders).createWxOrder(create);

        create.setStoreId(OTHER_TENANT_STORE);
        assertThrows(AccessDeniedException.class, () -> adminOrders.createWxOrder(create));
    }

    @Test
    void switchedTenantHeaderAndOtherTenantOrderIdAreDenied() {
        authenticate(TENANT_ADMIN, TENANT_1);
        login.setVisitTenantId(TENANT_2);
        TenantContextHolder.setTenantId(TENANT_2);
        assertThrows(AccessDeniedException.class, () -> adminOrders.getWxOrderPage(new WxOrderPageReqVO()));
        assertThrows(AccessDeniedException.class, () -> adminOrders.getWxOrder(ORDER_B));
        assertThrows(AccessDeniedException.class, () -> adminOrders.closeExpiredWxOrders(STORE_B, 10));
        verify(orders, never()).getWxOrder(ORDER_B);

        login.setVisitTenantId(null);
        TenantContextHolder.setTenantId(TENANT_1);
        // 即使底层意外返回外租户订单，门店归属复核也必须拒绝。
        when(orders.getWxOrder(OTHER_TENANT_ORDER)).thenReturn(order(OTHER_TENANT_ORDER, OTHER_TENANT_STORE));
        assertThrows(AccessDeniedException.class, () -> adminOrders.getWxOrder(OTHER_TENANT_ORDER));
        assertThrows(AccessDeniedException.class, () -> adminOrders.deleteWxOrder(OTHER_TENANT_ORDER));
        verify(stores, times(2)).getStore(OTHER_TENANT_STORE);
        verify(orders, never()).deleteWxOrder(OTHER_TENANT_ORDER);
    }

    private void authenticate(long userId, long tenantId) {
        login = new LoginUser();
        login.setId(userId);
        login.setTenantId(tenantId);
        login.setUserType(UserTypeEnum.ADMIN.getValue());
        TenantContextHolder.setTenantId(tenantId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(login, null, List.of()));
    }

    private static EmployeeDO employee(long id, long userId, long tenantId, long storeId) {
        EmployeeDO employee = new EmployeeDO();
        employee.setId(id);
        employee.setUserId(userId);
        employee.setTenantId(tenantId);
        employee.setStoreId(storeId);
        employee.setStatus(1);
        return employee;
    }

    private static StoreDO store(long id) {
        StoreDO store = new StoreDO();
        store.setId(id);
        return store;
    }

    private static WxOrderDO order(long id, long storeId) {
        WxOrderDO order = new WxOrderDO();
        order.setId(id);
        order.setStoreId(storeId);
        return order;
    }

}
