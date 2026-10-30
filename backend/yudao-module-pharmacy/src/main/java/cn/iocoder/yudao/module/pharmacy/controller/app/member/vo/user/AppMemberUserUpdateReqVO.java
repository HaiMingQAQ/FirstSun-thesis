package cn.iocoder.yudao.module.pharmacy.controller.app.member.vo.user;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Email;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Schema(description = "用户 APP - 会员基本信息修改 Request VO")
@Data
public class AppMemberUserUpdateReqVO {

    @Schema(description = "用户昵称", example = "张三")
    @Size(max = 30, message = "昵称长度不能超过 30 个字符")
    private String nickname;

    @Schema(description = "用户头像", example = "https://www.iocoder.cn/xxx.png")
    @Size(max = 512, message = "头像地址长度不能超过 512 个字符")
    private String avatar;

    @Schema(description = "性别", example = "1")
    private Integer sex;

    @Email
    @Size(max = 255)
    private String email;

    @JsonIgnore
    private boolean emailSpecified;

    public void setEmail(String email) {
        this.email = email == null ? null : email.trim();
        this.emailSpecified = true;
    }

}
