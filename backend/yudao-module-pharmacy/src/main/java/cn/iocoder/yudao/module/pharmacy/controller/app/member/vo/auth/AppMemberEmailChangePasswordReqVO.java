package cn.iocoder.yudao.module.pharmacy.controller.app.member.vo.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.ToString;

@Data
public class AppMemberEmailChangePasswordReqVO {
    @NotBlank
    @ToString.Exclude
    private String oldPassword;
    @NotBlank
    @ToString.Exclude
    private String password;
}
