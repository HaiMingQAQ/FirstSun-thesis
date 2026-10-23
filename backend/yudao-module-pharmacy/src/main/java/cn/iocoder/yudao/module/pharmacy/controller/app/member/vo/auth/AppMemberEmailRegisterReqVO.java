package cn.iocoder.yudao.module.pharmacy.controller.app.member.vo.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

@Data
public class AppMemberEmailRegisterReqVO {
    @NotBlank
    @Size(max = 254)
    private String email;
    @ToString.Exclude
    private String password;
}
