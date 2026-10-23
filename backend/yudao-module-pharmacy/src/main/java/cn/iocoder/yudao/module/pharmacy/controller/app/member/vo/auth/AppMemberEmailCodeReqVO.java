package cn.iocoder.yudao.module.pharmacy.controller.app.member.vo.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AppMemberEmailCodeReqVO {
    @NotBlank
    @Size(max = 254)
    private String email;
    @NotBlank
    @Pattern(regexp = "register|login")
    private String scene;
}
