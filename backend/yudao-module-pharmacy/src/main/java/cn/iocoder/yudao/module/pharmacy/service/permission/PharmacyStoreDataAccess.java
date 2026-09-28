package cn.iocoder.yudao.module.pharmacy.service.permission;

import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.base.EmployeeDO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.base.StoreDO;
import cn.iocoder.yudao.module.pharmacy.enums.EmployeeStatusEnum;
import cn.iocoder.yudao.module.pharmacy.service.base.EmployeeService;
import cn.iocoder.yudao.module.pharmacy.service.base.StoreService;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.module.system.enums.permission.RoleCodeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** 采购单和小程序订单管理共用的门店数据权限边界。 */
@Service
@RequiredArgsConstructor
public class PharmacyStoreDataAccess {

    private final PermissionApi permissionApi;
    private final EmployeeService employeeService;
    private final StoreService storeService;

    /** 校验当前用户可以访问指定门店。 */
    public void requireStore(Long storeId) {
        if (storeId == null) {
            throw new AccessDeniedException("门店编号无效");
        }
        scopeStoreId(storeId);
    }

    /**
     * 返回列表查询应使用的门店编号；租户管理员未指定门店时返回 null，表示本租户全部门店。
     */
    public Long scopeStoreId(Long requestedStoreId) {
        LoginUser login = requireLogin();
        if (permissionApi.hasAnyRoles(login.getId(), RoleCodeEnum.TENANT_ADMIN.getCode())) {
            if (requestedStoreId != null) {
                requireExistingStore(requestedStoreId);
            }
            return requestedStoreId;
        }

        EmployeeDO employee = employeeService.getEmployeeByUserId(login.getId());
        if (employee == null || !Objects.equals(employee.getUserId(), login.getId())
                || !EmployeeStatusEnum.isActive(employee.getStatus())
                || employee.getStoreId() == null || employee.getStoreId() <= 0
                || (requestedStoreId != null && !Objects.equals(requestedStoreId, employee.getStoreId()))) {
            throw new AccessDeniedException("无该门店数据权限");
        }
        requireExistingStore(employee.getStoreId());
        return employee.getStoreId();
    }

    private LoginUser requireLogin() {
        LoginUser login = SecurityFrameworkUtils.getLoginUser();
        Long tenantId = TenantContextHolder.getTenantId();
        var authentication = SecurityFrameworkUtils.getAuthentication();
        if (login == null || login.getId() == null || authentication == null
                || !authentication.isAuthenticated()
                || !Objects.equals(login.getUserType(), UserTypeEnum.ADMIN.getValue())
                || tenantId == null || tenantId <= 0 || TenantContextHolder.isIgnore()
                || !Objects.equals(tenantId, login.getTenantId())
                || (login.getVisitTenantId() != null
                    && !Objects.equals(tenantId, login.getVisitTenantId()))) {
            throw new AccessDeniedException("需要有效的管理端登录及本租户上下文");
        }
        return login;
    }

    private void requireExistingStore(Long storeId) {
        if (storeId == null || storeId <= 0) {
            throw new AccessDeniedException("门店编号无效");
        }
        StoreDO store = storeService.getStore(storeId);
        if (store == null || !Objects.equals(storeId, store.getId())) {
            throw new AccessDeniedException("无该门店数据权限");
        }
    }
}
