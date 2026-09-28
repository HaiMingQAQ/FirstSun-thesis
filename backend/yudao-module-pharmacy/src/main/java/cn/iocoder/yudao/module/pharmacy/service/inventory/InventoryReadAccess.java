package cn.iocoder.yudao.module.pharmacy.service.inventory;

import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.base.EmployeeDO;
import cn.iocoder.yudao.module.pharmacy.service.base.EmployeeService;
import cn.iocoder.yudao.module.pharmacy.service.permission.PharmacyStoreDataAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.util.Objects;

/** 未指定门店时使用员工本店；明确指定门店时复用采购与订单的门店权限。 */
@Service
@RequiredArgsConstructor
public class InventoryReadAccess {
    private final EmployeeService employeeService;
    private final PharmacyStoreDataAccess storeDataAccess;

    public Scope requireScope(Long requestedStoreId) {
        if (requestedStoreId != null) {
            // 采购收货和订单库存作业总是指定门店；复用其业务门禁，允许明确授权的租户管理员处理本租户门店。
            storeDataAccess.requireStore(requestedStoreId);
            return new Scope(TenantContextHolder.getTenantId(), requestedStoreId);
        }
        Long tenantId = TenantContextHolder.getTenantId();
        var login = SecurityFrameworkUtils.getLoginUser();
        var authentication = SecurityFrameworkUtils.getAuthentication();
        if (tenantId == null || tenantId < 0 || login == null || login.getId() == null
                || authentication == null || !authentication.isAuthenticated()
                || !Objects.equals(login.getUserType(), UserTypeEnum.ADMIN.getValue())
                || !Objects.equals(tenantId, login.getTenantId())
                || (login.getVisitTenantId() != null && !Objects.equals(tenantId, login.getVisitTenantId()))
                || TenantContextHolder.isIgnore()) {
            throw new AccessDeniedException("库存作业需要有效登录及租户上下文");
        }
        Long userId = login.getId();
        // A's mapper uses the framework tenant interceptor and logical deletion.
        EmployeeDO employee = employeeService.getEmployeeByUserId(userId);
        if (employee == null || !Objects.equals(employee.getUserId(), userId)
                || !Objects.equals(employee.getStatus(), 1)
                || employee.getStoreId() == null || employee.getStoreId() <= 0) {
            throw new AccessDeniedException("未关联在职员工或无该门店库存权限");
        }
        return new Scope(tenantId, employee.getStoreId());
    }

    public record Scope(long tenantId, long storeId) { }
}
