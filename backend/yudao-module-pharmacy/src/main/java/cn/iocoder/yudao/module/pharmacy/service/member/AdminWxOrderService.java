package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.controller.admin.member.vo.order.WxOrderPageReqVO;
import cn.iocoder.yudao.module.pharmacy.controller.admin.member.vo.order.WxOrderSaveReqVO;
import cn.iocoder.yudao.module.pharmacy.controller.admin.member.vo.order.WxOrderUpdateReqVO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.base.EmployeeDO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.WxOrderDO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.WxOrderLineDO;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.member.WxOrderMapper;
import cn.iocoder.yudao.module.pharmacy.enums.EmployeeStatusEnum;
import cn.iocoder.yudao.module.pharmacy.service.base.EmployeeService;
import cn.iocoder.yudao.module.pharmacy.service.permission.PharmacyStoreDataAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.pharmacy.enums.ErrorCodeConstants.PHARMACY_WX_ORDER_NOT_EXISTS;

/** 门店管理端订单边界；会员订单和支付通知继续使用各自的业务入口。 */
@Service
@RequiredArgsConstructor
public class AdminWxOrderService {

    private final PharmacyStoreDataAccess storeDataAccess;
    private final WxOrderService wxOrderService;
    private final WxOrderLineService wxOrderLineService;
    private final WxOrderMapper wxOrderMapper;
    private final EmployeeService employeeService;

    public Long createWxOrder(WxOrderSaveReqVO reqVO) {
        storeDataAccess.requireStore(reqVO.getStoreId());
        return wxOrderService.createWxOrder(reqVO);
    }

    public void updateWxOrder(WxOrderUpdateReqVO reqVO) {
        requireOrder(reqVO.getId());
        wxOrderService.updateWxOrder(reqVO);
    }

    public void deleteWxOrder(Long id) {
        requireOrder(id);
        wxOrderService.deleteWxOrder(id);
    }

    public WxOrderDO getWxOrder(Long id) {
        storeDataAccess.scopeStoreId(null);
        WxOrderDO order = wxOrderService.getWxOrder(id);
        if (order != null) {
            storeDataAccess.requireStore(order.getStoreId());
        }
        return order;
    }

    public PageResult<WxOrderDO> getWxOrderPage(WxOrderPageReqVO reqVO) {
        Long storeId = storeDataAccess.scopeStoreId(reqVO.getStoreId());
        return wxOrderMapper.selectAdminPage(reqVO, storeId);
    }

    public List<WxOrderLineDO> getWxOrderLineList(Long wxOrderId) {
        requireOrder(wxOrderId);
        return wxOrderLineService.getWxOrderLineListByWxOrderId(wxOrderId);
    }

    public void payWxOrder(Long id, String payNo) {
        requireOrder(id);
        wxOrderService.payWxOrder(id, payNo);
    }

    public void cancelWxOrder(Long id, String cancelReason) {
        requireOrder(id);
        wxOrderService.cancelWxOrder(id, cancelReason);
    }

    public void refundWxOrder(Long id, String refundReason) {
        requireOrder(id);
        wxOrderService.refundWxOrder(id, refundReason);
    }

    public void reserveWxOrder(Long id) {
        requireOrder(id);
        wxOrderService.reserveWxOrder(id);
    }

    public int closeExpiredWxOrders(Long storeId, Integer limit) {
        storeDataAccess.requireStore(storeId);
        return wxOrderService.closeExpiredWxOrders(storeId, limit);
    }

    public int releaseFrozenStockOfClosedOrders(Long storeId, Integer limit) {
        storeDataAccess.requireStore(storeId);
        return wxOrderService.releaseFrozenStockOfClosedOrders(storeId, limit);
    }

    public void startPicking(Long id) {
        requireOrder(id);
        wxOrderService.startPicking(id);
    }

    public void finishPicking(Long id) {
        requireOrder(id);
        wxOrderService.finishPicking(id);
    }

    public void verifyWxOrder(Long id, String pickupCode) {
        WxOrderDO order = requireOrder(id);
        LoginUser login = SecurityFrameworkUtils.getLoginUser();
        Long loginUserId = login.getId();
        EmployeeDO employee = employeeService.getEmployeeByUserId(loginUserId);
        if (employee == null || employee.getId() == null || employee.getId() <= 0
                || !Objects.equals(employee.getUserId(), loginUserId)
                || !Objects.equals(employee.getTenantId(), login.getTenantId())
                || !Objects.equals(employee.getTenantId(), TenantContextHolder.getTenantId())
                || !EmployeeStatusEnum.isActive(employee.getStatus())
                || !Objects.equals(employee.getStoreId(), order.getStoreId())) {
            throw new AccessDeniedException("当前用户无该订单门店的在职员工核销身份");
        }
        wxOrderService.verifyWxOrder(id, pickupCode, employee.getId());
    }

    private WxOrderDO requireOrder(Long id) {
        WxOrderDO order = getWxOrder(id);
        if (order == null) {
            throw exception(PHARMACY_WX_ORDER_NOT_EXISTS);
        }
        return order;
    }

}
