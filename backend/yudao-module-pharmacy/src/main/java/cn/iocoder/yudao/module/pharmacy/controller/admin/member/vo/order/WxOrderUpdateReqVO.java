package cn.iocoder.yudao.module.pharmacy.controller.admin.member.vo.order;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 小程序订单备注修改 Request VO")
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class WxOrderUpdateReqVO {

    @Schema(description = "订单编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "订单编号不能为空")
    private Long id;

    @Schema(description = "备注", example = "请尽快发货")
    @Size(max = 500, message = "备注长度不能超过 500 个字符")
    private String remark;

}
