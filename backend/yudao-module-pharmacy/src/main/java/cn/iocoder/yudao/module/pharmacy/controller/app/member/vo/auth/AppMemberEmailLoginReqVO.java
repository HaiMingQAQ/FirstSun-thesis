package cn.iocoder.yudao.module.pharmacy.controller.app.member.vo.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AppMemberEmailLoginReqVO {
    @NotBlank
    @Size(max = 254)
    private String email;
}
