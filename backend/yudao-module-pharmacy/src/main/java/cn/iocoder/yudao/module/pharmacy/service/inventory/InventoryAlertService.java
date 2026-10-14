package cn.iocoder.yudao.module.pharmacy.service.inventory;

import cn.iocoder.yudao.module.pharmacy.dal.mysql.inventory.InventoryAlertMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryAlertService {
    private final InventoryReadAccess access;
    private final InventoryAlertMapper mapper;

    public List<Stock> alerts(Long drugId, String keyword, int limit) {
        return mapper.selectStock(access.requireScope(null), drugId, keyword, true, limit);
    }

    public Stock stock(Long drugId) {
        var rows = mapper.selectStock(access.requireScope(null), drugId, null, false, 1);
        if (rows.isEmpty()) throw new IllegalArgumentException("药品不存在、未启用、未审核或本店已停用");
        return rows.get(0);
    }

    @Data
    public static class Stock {
        private Long drugId;
        private String drugCode;
        private String drugName;
        private String specification;
        private String unit;
        private Integer minStock;
        private Integer isRx;
        private Integer storageCond;
        private Long availableQty;

        public boolean isLowStock() { return minStock != null && minStock > 0 && availableQty < minStock; }
    }
}
