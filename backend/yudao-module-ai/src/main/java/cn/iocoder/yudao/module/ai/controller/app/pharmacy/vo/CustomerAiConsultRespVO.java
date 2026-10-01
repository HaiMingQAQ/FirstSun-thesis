package cn.iocoder.yudao.module.ai.controller.app.pharmacy.vo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
public record CustomerAiConsultRespVO(String clientMessageId, String status, String answer,
        String source, LocalDateTime queriedAt, boolean truncated, List<Product> products) {
    public record Product(Long id, String name, String genericName, String specification,
            String manufacturer, String approvalNo, String imageUrl, BigDecimal price,
            Integer availableQty, Long storeId) { }
}
