package cn.iocoder.yudao.module.pharmacy.controller.app.member;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.pharmacy.controller.app.member.vo.auth.AppMemberEmailLoginReqVO;
import cn.iocoder.yudao.module.pharmacy.controller.app.member.vo.auth.AppMemberEmailResetPasswordReqVO;
import cn.iocoder.yudao.module.pharmacy.controller.app.member.vo.auth.AppMemberEmailChangePasswordReqVO;
import cn.iocoder.yudao.module.pharmacy.service.member.MemberEmailAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.common.util.servlet.ServletUtils.getClientIP;
import static cn.iocoder.yudao.module.pharmacy.service.member.AppMemberAccess.requireMember;

@Tag(name = "用户 APP - 会员邮箱密码安全")
@RestController
@RequestMapping("/member/auth")
@Validated
public class AppMemberEmailPasswordController {
    private static final String EMAIL_CODE_HEADER = "X-Email-Code";

    @Resource
    private MemberEmailAuthService memberEmailAuthService;

    @PostMapping("/send-email-reset-code")
    @Operation(summary = "请求登录邮箱重置密码验证码，统一发送提示")
    @PermitAll
    public CommonResult<Boolean> sendResetCode(@RequestBody @Valid AppMemberEmailLoginReqVO request) {
        memberEmailAuthService.sendResetCode(request.getEmail(), getClientIP());
        return success(true);
    }

    @PostMapping("/email-reset-password")
    @Operation(summary = "登录邮箱验证码重置密码，撤销旧会话")
    @ApiAccessLog(sanitizeKeys = {"password"})
    @PermitAll
    public CommonResult<Boolean> resetPassword(
            @RequestBody @Valid AppMemberEmailResetPasswordReqVO request,
            @RequestHeader(value = EMAIL_CODE_HEADER, required = false) String code) {
        memberEmailAuthService.resetPassword(request.getEmail(), request.getPassword(), code, getClientIP());
        return success(true);
    }

    @PostMapping("/email-change-password")
    @Operation(summary = "当前邮箱会员使用原密码修改密码，撤销旧会话")
    @ApiAccessLog(sanitizeKeys = {"oldPassword", "password"})
    public CommonResult<Boolean> changePassword(@RequestBody @Valid AppMemberEmailChangePasswordReqVO request) {
        memberEmailAuthService.changePassword(requireMember(), request.getOldPassword(), request.getPassword(), getClientIP());
        return success(true);
    }
}
