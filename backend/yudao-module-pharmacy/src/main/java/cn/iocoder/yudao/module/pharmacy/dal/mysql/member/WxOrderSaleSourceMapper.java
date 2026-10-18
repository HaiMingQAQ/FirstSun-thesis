package cn.iocoder.yudao.module.pharmacy.dal.mysql.member;

import cn.iocoder.yudao.module.pharmacy.dal.dataobject.sale.PhSaleOrderDO;
import lombok.Data;
import org.apache.ibatis.annotations.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Payment-time allocations and cost snapshots; never performs inventory writes. */
@Mapper
public interface WxOrderSaleSourceMapper {
    @Select("SELECT * FROM ph_sale_order WHERE wx_order_id=#{orderId} AND tenant_id=#{tenantId} AND deleted=0")
    PhSaleOrderDO existing(@Param("orderId") Long orderId,@Param("tenantId") Long tenantId);

    // Fulfillment reads retained drug/batch archives even after logical deletion; flow/allocation evidence must remain active.
    @Select("""
        SELECT a.id AS allocation_id, a.wx_order_line_id AS line_id, a.drug_id,
               a.batch_id,a.location_id,a.qty,f.id AS flow_id,f.out_qty AS flow_qty,
               f.unit_cost,f.batch_no,b.expiry_date,d.is_rx
        FROM ph_wx_order_line_alloc a
        JOIN ph_inv_flow f ON f.tenant_id=a.tenant_id AND f.store_id=#{storeId}
          AND f.biz_no=a.order_no AND f.biz_line_id=a.out_biz_line_id
          AND f.drug_id=a.drug_id AND f.batch_id=a.batch_id AND f.location_id=a.location_id
          AND ((f.flow_type=20 AND f.biz_type=2) OR (f.flow_type=82 AND f.biz_type=6))
          AND f.deleted=0 AND f.in_qty=0
        JOIN ph_inv_batch b ON b.tenant_id=a.tenant_id AND b.id=a.batch_id
          AND b.drug_id=a.drug_id AND b.store_id=#{storeId}
        JOIN ph_drug d ON d.tenant_id=a.tenant_id AND d.id=a.drug_id
        WHERE a.tenant_id=#{tenantId} AND a.wx_order_id=#{orderId} AND a.order_no=#{orderNo}
          AND a.deleted=0 AND a.status=1 AND a.returned_qty=0
        ORDER BY a.wx_order_line_id,a.id
        """)
    List<AllocationSource> allocations(@Param("orderId") Long orderId,@Param("tenantId") Long tenantId,
            @Param("storeId") Long storeId,@Param("orderNo") String orderNo);

    @Data
    class AllocationSource {
        private Long allocationId,lineId,drugId,batchId,locationId,flowId;
        private Integer qty,flowQty,isRx;
        private BigDecimal unitCost;
        private String batchNo;
        private LocalDate expiryDate;
    }
}
