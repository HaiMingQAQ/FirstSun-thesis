package cn.iocoder.yudao.module.pharmacy.dal.mysql.inventory;

import cn.iocoder.yudao.module.pharmacy.service.inventory.InventoryAlertService.Stock;
import cn.iocoder.yudao.module.pharmacy.service.inventory.InventoryReadAccess.Scope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/** Same usable-location inventory rules as the catalogue, including offline drugs for procurement. */
@Mapper
public interface InventoryAlertMapper {
    @Select("""
        <script>
        SELECT d.id AS drug_id, d.drug_code, d.generic_name AS drug_name, d.specification, d.unit,
               d.min_stock, d.is_rx, d.storage_cond,
               COALESCE((SELECT SUM(GREATEST(s.qty-s.qty_frozen,0))
                 FROM ph_inv_location_stock s
                 JOIN ph_inv_batch b ON b.id=s.batch_id AND b.tenant_id=s.tenant_id
                 JOIN ph_location l ON l.id=s.location_id AND l.tenant_id=s.tenant_id AND l.warehouse_id=b.warehouse_id
                 JOIN ph_warehouse w ON w.id=b.warehouse_id AND w.tenant_id=b.tenant_id AND w.store_id=b.store_id
                 WHERE b.tenant_id=#{scope.tenantId} AND b.store_id=#{scope.storeId} AND b.drug_id=d.id
                   AND s.deleted=0 AND b.deleted=0 AND l.deleted=0 AND w.deleted=0
                   AND l.status=1 AND w.status=1 AND b.quality_status=0 AND b.expiry_date &gt;= CURRENT_DATE),0) AS available_qty
        FROM ph_drug d
        JOIN ph_store st ON st.id=#{scope.storeId} AND st.tenant_id=d.tenant_id AND st.deleted=0 AND st.status=1
        WHERE d.tenant_id=#{scope.tenantId} AND d.deleted=0 AND d.status=1 AND d.approve_status=1
        <if test="drugId != null">AND d.id=#{drugId}</if>
        <if test="keyword != null and keyword != ''">AND (d.generic_name LIKE CONCAT('%',#{keyword},'%') OR d.drug_code LIKE CONCAT('%',#{keyword},'%'))</if>
        <if test="alertsOnly">HAVING min_stock &gt; 0 AND available_qty &lt; min_stock</if>
        ORDER BY d.id LIMIT #{limit}
        </script>
        """)
    List<Stock> selectStock(@Param("scope") Scope scope, @Param("drugId") Long drugId,
                           @Param("keyword") String keyword, @Param("alertsOnly") boolean alertsOnly,
                           @Param("limit") int limit);
}
