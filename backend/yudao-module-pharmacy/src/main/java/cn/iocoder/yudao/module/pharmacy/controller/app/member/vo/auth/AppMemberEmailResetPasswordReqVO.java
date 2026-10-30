package cn.iocoder.yudao.module.pharmacy.controller.app.member.vo.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

@Data
public class AppMemberEmailResetPasswordReqVO {
    @NotBlank
    @Size(max = 254)
    private String email;
    @NotBlank
    @ToString.Exclude
    private String password;
}
