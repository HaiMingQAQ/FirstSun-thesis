package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.iocoder.yudao.module.pay.enums.order.PayOrderStatusEnum;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.*;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.sale.*;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.member.*;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.sale.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.*;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.invalidParamException;

/** Completes the accounting of an already-paid order inside its fulfillment transaction. */
@Service
@RequiredArgsConstructor
@Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
public class WxOrderSalePostingService {
    private final WxOrderPaymentAccess paymentAccess;
    private final WxOrderLineMapper lines;
    private final WxOrderLineAllocMapper allocations;
    private final WxOrderSaleSourceMapper source;
    private final SaleOrderMapper sales;
    private final SaleOrderLineMapper saleLines;
    private final SalePaymentMapper payments;

    public Long post(Long orderId) {
        var order=paymentAccess.lockOrder(orderId);
        require(Objects.equals(order.getStatus(),4)&&Objects.equals(order.getPayStatus(),1)
                &&order.getFinishAt()!=null&&order.getPaidAt()!=null,"订单尚未支付并完成");
        var existing=source.existing(orderId,WxOrderPaymentAccess.tenantId());
        if(existing!=null){
            require(Objects.equals(existing.getSource(),1)&&Objects.equals(existing.getStatus(),1)
                    &&Objects.equals(existing.getStoreId(),order.getStoreId())&&Objects.equals(existing.getMemberId(),order.getMemberId()),"已有销售来源不一致");
            return existing.getId();
        }
        Long paymentId=order.getPayOrderId();
        if(paymentId==null){
            try{paymentId=Long.valueOf(order.getPayNo());}catch(RuntimeException e){throw invalidParamException("订单缺少可核对的支付关联");}
        }
        var payment=paymentAccess.lockPayment(order,paymentId);
        require(Objects.equals(payment.getStatus(),PayOrderStatusEnum.SUCCESS.getStatus())&&payment.getSuccessTime()!=null
                &&Objects.equals(payment.getRefundPrice(),0),"关联支付未成功或已有退款");
        String channel=payment.getChannelCode();
        require(channel!=null&&(channel.equals("mock")||channel.startsWith("wx_")),"现有线上支付渠道不支持销售入账");
        var original=lines.selectListByWxOrderId(orderId);
        require(original!=null&&!original.isEmpty(),"订单缺少商品快照");
        Map<Long,WxOrderLineDO> byId=new LinkedHashMap<>();BigDecimal goods=BigDecimal.ZERO;
        for(var line:original){
            require(Objects.equals(line.getWxOrderId(),orderId)&&line.getQty()!=null&&line.getQty()>0
                    &&line.getDrugName()!=null&&!line.getDrugName().isBlank()&&!byId.containsKey(line.getId()),"订单商品快照无效");
            require(money(line.getPrice()).multiply(BigDecimal.valueOf(line.getQty())).compareTo(money(line.getLineAmount()))==0,"订单行金额与快照单价数量不一致");
            goods=goods.add(line.getLineAmount());byId.put(line.getId(),line);
        }
        require(goods.compareTo(money(order.getGoodsAmount()))==0,"订单商品合计不一致");
        BigDecimal freight=money(order.getFreightAmount()),discount=money(order.getDiscountAmount()),coupon=money(order.getCouponAmount()),points=money(order.getPointDeductAmount());
        BigDecimal payable=goods.add(freight).subtract(discount).subtract(coupon).subtract(points);
        require(payable.signum()>=0&&payable.compareTo(money(order.getPayableAmount()))==0,"订单应付金额不一致");
        var allocated=allocations.selectListByWxOrderId(orderId);
        var sources=source.allocations(orderId,WxOrderPaymentAccess.tenantId(),order.getStoreId(),order.getOrderNo());
        require(allocated!=null&&!allocated.isEmpty()&&sources.size()==allocated.size(),"订单出库来源不完整");
        Map<Long,WxOrderLineAllocDO> allocationById=new HashMap<>();
        for(var allocation:allocated){
            require(Objects.equals(allocation.getStatus(),WxOrderLineAllocDO.STATUS_OUT)&&Objects.equals(allocation.getReturnedQty(),0)
                    &&Objects.equals(allocation.getOrderNo(),order.getOrderNo())&&Objects.equals(allocation.getWxOrderId(),orderId),"订单出库分配无效");
            require(allocationById.put(allocation.getId(),allocation)==null,"出库分配重复");
        }
        Map<Long,Integer> quantities=new HashMap<>();Set<Long> flows=new HashSet<>(),seenAllocations=new HashSet<>();
        List<PhSaleOrderLineDO> details=new ArrayList<>();BigDecimal cost=BigDecimal.ZERO;
        for(var snapshot:sources){
            var line=byId.get(snapshot.getLineId());var allocation=allocationById.get(snapshot.getAllocationId());
            require(line!=null&&allocation!=null&&seenAllocations.add(snapshot.getAllocationId())&&flows.add(snapshot.getFlowId())
                    &&Objects.equals(allocation.getWxOrderLineId(),line.getId())&&Objects.equals(snapshot.getDrugId(),line.getDrugId())
                    &&Objects.equals(allocation.getDrugId(),line.getDrugId())&&Objects.equals(allocation.getBatchId(),snapshot.getBatchId())
                    &&Objects.equals(allocation.getLocationId(),snapshot.getLocationId())&&Objects.equals(allocation.getQty(),snapshot.getQty())
                    &&snapshot.getQty()!=null&&snapshot.getQty()>0&&Objects.equals(snapshot.getFlowQty(),snapshot.getQty())
                    &&snapshot.getUnitCost()!=null&&snapshot.getUnitCost().signum()>=0&&snapshot.getExpiryDate()!=null
                    &&(Objects.equals(snapshot.getIsRx(),0)||Objects.equals(snapshot.getIsRx(),1)),"出库批次、分类或数量成本无法核对");
            quantities.merge(line.getId(),snapshot.getQty(),Math::addExact);
            var detail=new PhSaleOrderLineDO();detail.setLineNo(details.size()+1);detail.setDrugId(line.getDrugId());
            detail.setQty(snapshot.getQty());detail.setBatchId(snapshot.getBatchId());detail.setLocationId(snapshot.getLocationId());
            detail.setBatchNo(snapshot.getBatchNo());detail.setExpiryDate(snapshot.getExpiryDate());detail.setCostPrice(snapshot.getUnitCost());
            detail.setDrugName(line.getDrugName());detail.setSpecification(line.getSpecification());detail.setUnit(line.getUnit());
            detail.setPrice(line.getPrice());detail.setNormalPrice(line.getPrice());detail.setDiscount(BigDecimal.ZERO);
            detail.setLineAmount(line.getPrice().multiply(BigDecimal.valueOf(snapshot.getQty())));
            detail.setIsRx(snapshot.getIsRx());detail.setPrescId(order.getPrescId());detail.setReturnedQty(0);detail.setIsGift(0);
            details.add(detail);cost=cost.add(snapshot.getUnitCost().multiply(BigDecimal.valueOf(snapshot.getQty())));
        }
        for(var line:original)require(Objects.equals(quantities.get(line.getId()),line.getQty()),"出库数量与订单不一致");
        var sale=new PhSaleOrderDO();sale.setOrderNo("WXSO-"+order.getId());sale.setSource(1);sale.setWxOrderId(orderId);
        sale.setStoreId(order.getStoreId());sale.setMemberId(order.getMemberId());sale.setPosNo("");sale.setSaleType(0);sale.setReturnFlag(0);
        sale.setTotalQty(quantities.values().stream().reduce(0,Math::addExact));sale.setSubtotal(goods);sale.setFreightAmount(freight);
        sale.setDiscountAmount(discount);sale.setCouponAmount(coupon);sale.setPointsDeduct(points);sale.setPayableAmount(payable);
        sale.setPaidAmount(BigDecimal.valueOf(payment.getPrice(),2));sale.setChangeAmount(BigDecimal.ZERO);
        sale.setCostAmount(cost.setScale(2,RoundingMode.HALF_UP));sale.setPointsEarned(order.getPointEarned());
        sale.setStatus(1);sale.setOfflineFlag(0);sale.setSaleTime(order.getFinishAt());sale.setRemark("线上订单履约入账："+order.getOrderNo());
        require(sales.insert(sale)==1,"销售主单写入失败");
        for(var detail:details){detail.setOrderId(sale.getId());require(saleLines.insert(detail)==1,"销售明细写入失败");}
        var receipt=new PhSalePaymentDO();receipt.setOrderId(sale.getId());receipt.setPaymentNo("WXSO-PAY-"+orderId);
        receipt.setPayOrderId(paymentId);receipt.setPayNo(payment.getChannelOrderNo());receipt.setPayMethod(2);
        receipt.setChannel(channel);receipt.setStatus(1);receipt.setPaidAt(payment.getSuccessTime());receipt.setPayAmount(sale.getPaidAmount());
        require(payments.insert(receipt)==1,"销售支付写入失败");return sale.getId();
    }
    private static BigDecimal money(BigDecimal value){
        require(value!=null&&value.signum()>=0,"订单缺少有效金额快照");
        try{return value.setScale(2,RoundingMode.UNNECESSARY);}catch(ArithmeticException e){throw invalidParamException("订单金额精度无效");}
    }
    private static void require(boolean condition,String message){if(!condition)throw invalidParamException(message);}
}
