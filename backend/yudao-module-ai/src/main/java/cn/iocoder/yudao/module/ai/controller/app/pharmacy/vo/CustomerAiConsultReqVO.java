package cn.iocoder.yudao.module.ai.controller.app.pharmacy.vo;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data
public class CustomerAiConsultReqVO {
    @NotBlank @Size(max = 64) @Pattern(regexp = "[a-zA-Z0-9_-]+")
    private String clientMessageId;
    @NotBlank @Size(max = 500)
    private String content;
    @NotNull @Positive
    private Long storeId;
}
