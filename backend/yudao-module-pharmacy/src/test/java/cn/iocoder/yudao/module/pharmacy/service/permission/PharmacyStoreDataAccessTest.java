package cn.iocoder.yudao.module.pharmacy.service.permission;

import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.base.EmployeeDO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.base.StoreDO;
import cn.iocoder.yudao.module.pharmacy.service.base.EmployeeService;
import cn.iocoder.yudao.module.pharmacy.service.base.StoreService;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.module.system.enums.permission.RoleCodeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PharmacyStoreDataAccessTest {

    private PermissionApi permissionApi;
    private EmployeeService employeeService;
    private StoreService storeService;
    private PharmacyStoreDataAccess access;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(7L);
        permissionApi = mock(PermissionApi.class);
        employeeService = mock(EmployeeService.class);
        storeService = mock(StoreService.class);
        access = new PharmacyStoreDataAccess(permissionApi, employeeService, storeService);

        when(employeeService.getEmployeeByUserId(101L)).thenReturn(employee(101L, 10L, 1));
        when(employeeService.getEmployeeByUserId(102L)).thenReturn(employee(102L, 20L, 1));
        when(storeService.getStore(10L)).thenReturn(store(10L));
        when(storeService.getStore(20L)).thenReturn(store(20L));
        // 30 号门店属于另一租户，在当前租户的 StoreService 查询中不存在。
        when(permissionApi.hasAnyRoles(103L, RoleCodeEnum.TENANT_ADMIN.getCode())).thenReturn(true);
        login(101L, 7L);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    @Test
    void employeesAreLimitedToTheirOwnStores() {
        assertEquals(10L, access.scopeStoreId(null));
        assertEquals(10L, access.scopeStoreId(10L));
        access.requireStore(10L);
        assertThrows(AccessDeniedException.class, () -> access.scopeStoreId(20L));
        assertThrows(AccessDeniedException.class, () -> access.requireStore(20L));

        login(102L, 7L);
        assertEquals(20L, access.scopeStoreId(null));
        assertThrows(AccessDeniedException.class, () -> access.requireStore(10L));
    }

    @Test
    void tenantAdminCanAccessBothStoresButNotAnotherTenantsStore() {
        login(103L, 7L);
        assertNull(access.scopeStoreId(null));
        assertEquals(10L, access.scopeStoreId(10L));
        assertEquals(20L, access.scopeStoreId(20L));
        access.requireStore(20L);
        assertThrows(AccessDeniedException.class, () -> access.requireStore(30L));
    }

    @Test
    void missingInactiveOrInvalidEmployeeBindingHasNoAccess() {
        login(105L, 7L);
        assertThrows(AccessDeniedException.class, () -> access.scopeStoreId(null));

        login(101L, 7L);
        when(employeeService.getEmployeeByUserId(101L)).thenReturn(employee(101L, 10L, 0));
        assertThrows(AccessDeniedException.class, () -> access.requireStore(10L));

        when(employeeService.getEmployeeByUserId(101L)).thenReturn(employee(101L, 0L, 1));
        assertThrows(AccessDeniedException.class, () -> access.scopeStoreId(null));

        when(employeeService.getEmployeeByUserId(101L)).thenReturn(employee(101L, 10L, 1));
        when(storeService.getStore(10L)).thenReturn(null);
        assertThrows(AccessDeniedException.class, () -> access.scopeStoreId(null));
    }

    @Test
    void rejectsSpoofedEmployeeLinkAndInvalidStoreIds() {
        when(employeeService.getEmployeeByUserId(101L)).thenReturn(employee(102L, 10L, 1));
        assertThrows(AccessDeniedException.class, () -> access.requireStore(10L));

        login(103L, 7L);
        assertThrows(AccessDeniedException.class, () -> access.requireStore(null));
        assertThrows(AccessDeniedException.class, () -> access.requireStore(-1L));
    }

    @Test
    void rejectsTenantHeaderAndVisitTenantSwitch() {
        login(104L, 8L);
        assertThrows(AccessDeniedException.class, () -> access.scopeStoreId(null));

        LoginUser user = login(103L, 7L);
        user.setVisitTenantId(8L);
        assertThrows(AccessDeniedException.class, () -> access.scopeStoreId(null));

        TenantContextHolder.setTenantId(8L);
        assertThrows(AccessDeniedException.class, () -> access.requireStore(30L));
    }

    @Test
    void rejectsNonPositiveTenantEvenWhenLoginMatches() {
        login(101L, 0L);
        TenantContextHolder.setTenantId(0L);
        assertThrows(AccessDeniedException.class, () -> access.scopeStoreId(null));

        login(101L, -1L);
        TenantContextHolder.setTenantId(-1L);
        assertThrows(AccessDeniedException.class, () -> access.requireStore(10L));
    }

    @Test
    void rejectsMissingAuthenticationMemberAndIgnoredTenant() {
        SecurityContextHolder.clearContext();
        assertThrows(AccessDeniedException.class, () -> access.scopeStoreId(null));

        LoginUser user = login(101L, 7L);
        user.setUserType(UserTypeEnum.MEMBER.getValue());
        assertThrows(AccessDeniedException.class, () -> access.scopeStoreId(null));

        user.setUserType(UserTypeEnum.ADMIN.getValue());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null));
        assertThrows(AccessDeniedException.class, () -> access.scopeStoreId(null));

        login(101L, 7L);
        TenantContextHolder.setIgnore(true);
        assertThrows(AccessDeniedException.class, () -> access.scopeStoreId(null));
    }

    private static EmployeeDO employee(Long userId, Long storeId, Integer status) {
        EmployeeDO employee = new EmployeeDO();
        employee.setUserId(userId);
        employee.setStoreId(storeId);
        employee.setStatus(status);
        return employee;
    }

    private static StoreDO store(Long id) {
        StoreDO store = new StoreDO();
        store.setId(id);
        return store;
    }

    private static LoginUser login(Long userId, Long tenantId) {
        LoginUser user = new LoginUser();
        user.setId(userId);
        user.setTenantId(tenantId);
        user.setUserType(UserTypeEnum.ADMIN.getValue());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        return user;
    }
}
