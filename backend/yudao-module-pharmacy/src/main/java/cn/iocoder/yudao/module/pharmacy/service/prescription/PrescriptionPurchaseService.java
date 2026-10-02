package cn.iocoder.yudao.module.pharmacy.service.prescription;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.controller.admin.prescription.vo.PrescItemVO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.prescription.*;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.*;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.prescription.*;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.inventory.AppAvailableInventoryMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.invalidParamException;
/** Called inside existing order transactions; never authorizes by frontend state. */
@Service @RequiredArgsConstructor
public class PrescriptionPurchaseService {
    private final PrescRecordMapper prescriptions;
    private final PrescriptionUseMapper uses;
    private final AppAvailableInventoryMapper stock;
    private final PrescriptionNotificationService notifications;
    private final cn.iocoder.yudao.module.pharmacy.service.base.StoreService stores;
    private final cn.iocoder.yudao.module.pharmacy.service.base.DrugService drugs;
    private final cn.iocoder.yudao.module.pharmacy.dal.mysql.member.WxOrderLineMapper lines;
    public PhPrescRecordDO lockApproved(Long id,Long member,Long store) {
        if(TenantContextHolder.getTenantId()==null||TenantContextHolder.isIgnore())throw new AccessDeniedException("租户上下文无效");
        var currentStore=stores.getStore(store);
        if(currentStore==null||!Objects.equals(currentStore.getStatus(),1))throw new AccessDeniedException("门店不可用");
        var record=prescriptions.lock(id);
        if(record==null||!Objects.equals(record.getWxMemberId(),member)||!Objects.equals(record.getStoreId(),store))
            throw new AccessDeniedException("处方不属于当前会员与门店");
        if(!Objects.equals(record.getStatus(),0)||!Objects.equals(record.getReviewStatus(),1)||record.getApprovedUntil()==null
                ||!record.getApprovedUntil().isAfter(LocalDateTime.now())||record.getApprovedItems()==null)
            throw invalidParamException("处方未通过、已失效或超过有效期");
        return record;
    }
    public void checkCart(PhPrescRecordDO record,List<WxCartDO> cart) {
        if(uses.active(record.getId())!=null)throw invalidParamException("该处方已有使用中的订单");
        var approved=JsonUtils.parseArray(record.getApprovedItems(),PrescItemVO.class).stream()
                .collect(Collectors.toMap(PrescItemVO::getDrugId,PrescItemVO::getQty));
        var actual=new HashMap<Long,Integer>();
        for(var row:cart){if(row.getQty()==null||row.getQty()<1||actual.put(row.getDrugId(),row.getQty())!=null)throw invalidParamException("结算明细不合法");}
        if(!approved.equals(actual))throw invalidParamException("必须按药师核准的固定明细和数量整单购买");
        requireDrugs(approved.keySet());
        var available=stock.selectAvailable(TenantContextHolder.getTenantId(),record.getStoreId(),new ArrayList<>(approved.keySet())).stream()
                .collect(Collectors.toMap(v->v.getDrugId(),v->v.getQtyAvail()));
        for(var item:approved.entrySet())if(available.getOrDefault(item.getKey(),0)<item.getValue())throw invalidParamException("核准商品库存不足，请联系门店");
    }
    public void hold(PhPrescRecordDO record,Long orderId) {
        var row=new PrescriptionUseDO();row.setTenantId(TenantContextHolder.getTenantId());row.setPrescId(record.getId());
        row.setWxOrderId(orderId);row.setStatus("HELD");row.setItemsSnapshot(record.getApprovedItems());uses.insert(row);
    }
    public void requirePayment(WxOrderDO order) {
        if(order.getPrescId()==null)return;
        var record=lockApproved(order.getPrescId(),order.getMemberId(),order.getStoreId());
        var use=uses.order(order.getId());
        if(use==null||!Objects.equals(use.getPrescId(),record.getId())||!Objects.equals(use.getStatus(),"HELD")
                ||!Objects.equals(use.getItemsSnapshot(),record.getApprovedItems()))throw invalidParamException("处方使用记录不支持本次支付");
        var approved=JsonUtils.parseArray(record.getApprovedItems(),PrescItemVO.class).stream()
                .collect(Collectors.toMap(PrescItemVO::getDrugId,PrescItemVO::getQty));
        var actual=new HashMap<Long,Integer>();
        for(var line:lines.selectListByWxOrderId(order.getId()))
            if(line.getQty()==null||line.getQty()<1||actual.put(line.getDrugId(),line.getQty())!=null)
                throw invalidParamException("订单处方明细不合法");
        if(!approved.equals(actual))throw invalidParamException("订单明细与药师核准内容不一致");
        requireDrugs(approved.keySet());
    }
    private void requireDrugs(Set<Long> ids) {
        for(Long id:ids) {
            var drug=drugs.getDrug(id);
            if(drug==null||!Objects.equals(drug.getStatus(),1)||!Objects.equals(drug.getApproveStatus(),1)
                    ||!Objects.equals(drug.getSaleableOnline(),1)||Objects.equals(drug.getDrugType(),3)
                    ||Objects.equals(drug.getIsSpecial(),1)||Objects.equals(drug.getIsPseudoephedrine(),1))
                throw invalidParamException("核准药品已不支持在线购买，请联系药师");
        }
    }
    public void paid(WxOrderDO order) {
        if(order.getPrescId()==null)return;
        requirePayment(order);
        int updated=uses.update(useUpdate(),new LambdaUpdateWrapper<PrescriptionUseDO>()
                .eq(PrescriptionUseDO::getWxOrderId,order.getId()).eq(PrescriptionUseDO::getStatus,"HELD").set(PrescriptionUseDO::getStatus,"PAID"));
        if(updated!=1)throw invalidParamException("处方使用状态已变化");
    }
    public void releaseUnpaid(WxOrderDO order,String reason) {
        if(order.getPrescId()==null||!Objects.equals(order.getPayStatus(),0))return;
        var record=prescriptions.lock(order.getPrescId());if(record==null)throw invalidParamException("处方记录不存在");
        int updated=uses.update(useUpdate(),new LambdaUpdateWrapper<PrescriptionUseDO>()
                .eq(PrescriptionUseDO::getWxOrderId,order.getId()).eq(PrescriptionUseDO::getPrescId,record.getId())
                .eq(PrescriptionUseDO::getStatus,"HELD").set(PrescriptionUseDO::getStatus,"RELEASED").set(PrescriptionUseDO::getReleaseReason,reason));
        if(updated==1)notifications.send(record.getWxMemberId(),record.getId(),"未支付订单已关闭，处方可在有效期内重新使用");
    }
    private PrescriptionUseDO useUpdate() {
        var row=new PrescriptionUseDO();
        var actor=cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId();
        row.setUpdater(actor==null ? "SYSTEM" : actor.toString());
        return row;
    }
}
