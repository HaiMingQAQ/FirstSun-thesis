package cn.iocoder.yudao.module.pharmacy.service.prescription;
import cn.iocoder.yudao.module.pharmacy.service.permission.PharmacyStoreDataAccess;
import cn.iocoder.yudao.module.pharmacy.service.base.EmployeeService;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.base.EmployeeDO;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import java.time.LocalDate;
import java.util.Objects;
@Service @RequiredArgsConstructor
public class PrescriptionStaffAccess {
    private final PharmacyStoreDataAccess stores;
    private final EmployeeService employees;
    public EmployeeDO requirePharmacist(Long storeId) {
        stores.requireStore(storeId);
        var employee = employees.getEmployeeByUserId(SecurityFrameworkUtils.getLoginUserId());
        if (employee == null || !Objects.equals(employee.getStoreId(),storeId)
                || !Objects.equals(employee.getTenantId(),TenantContextHolder.getTenantId())
                || !Objects.equals(employee.getStatus(),1) || !Objects.equals(employee.getPosition(),2)
                || employee.getPharmacistNo() == null || employee.getPharmacistNo().isBlank()
                || employee.getLicenseExpire() == null || employee.getLicenseExpire().isBefore(LocalDate.now()))
            throw new AccessDeniedException("需要本门店在职且资质有效的药师");
        return employee;
    }
}
