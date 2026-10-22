package cn.iocoder.yudao.module.pharmacy.controller.app.member.vo.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.ToString;

@Data
public class AppMemberFirstPasswordReqVO {
    @NotBlank
    @ToString.Exclude
    private String password;
}
