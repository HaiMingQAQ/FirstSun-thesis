package cn.iocoder.yudao.module.pharmacy.controller.app.prescription.vo;

import cn.iocoder.yudao.module.pharmacy.controller.admin.prescription.vo.PrescItemVO;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Schema(description = "用户 APP - 处方登记 Request VO")
@Data
public class AppPrescRecordCreateReqVO {

    @Schema(description = "履约门店编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "407")
    @NotNull(message = "门店不能为空")
    @jakarta.validation.constraints.Positive
    private Long storeId;

    @Schema(description = "患者姓名", requiredMode = Schema.RequiredMode.REQUIRED, example = "顾晨")
    @jakarta.validation.constraints.NotBlank(message = "患者姓名不能为空")
    @Size(max = 32)
    private String patientName;

    @Schema(description = "开具医院", example = "厦门市第一医院")
    @Size(max = 100)
    private String hospital;

    @Schema(description = "医师姓名", example = "张医生")
    @Size(max = 32)
    private String doctorName;

    @NotEmpty @Size(max = 3)
    private List<@NotNull @jakarta.validation.constraints.Positive Long> materialIds;

    @jakarta.validation.constraints.NotBlank @Size(max = 64)
    @jakarta.validation.constraints.Pattern(regexp = "[a-zA-Z0-9_-]+")
    private String clientRequestId;

    @Schema(description = "处方药品明细（药品 ID 与核准数量）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "处方药品明细不能为空")
    @Valid @Size(max = 20)
    private List<@NotNull PrescItemVO> items;

}
