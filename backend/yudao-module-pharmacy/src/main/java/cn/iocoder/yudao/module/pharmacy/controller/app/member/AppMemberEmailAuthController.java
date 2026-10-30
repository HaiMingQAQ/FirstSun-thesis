package cn.iocoder.yudao.module.pharmacy.controller.app.member;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.pharmacy.controller.app.member.vo.auth.*;
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

@Tag(name = "用户 APP - 会员邮箱认证")
@RestController
@RequestMapping("/member/auth")
@Validated
public class AppMemberEmailAuthController {
    private static final String EMAIL_CODE_HEADER = "X-Email-Code";

    @Resource
    private MemberEmailAuthService memberEmailAuthService;

    @PostMapping("/send-email-code")
    @Operation(summary = "发送邮箱注册或登录验证码")
    @PermitAll
    public CommonResult<Boolean> sendCode(@RequestBody @Valid AppMemberEmailCodeReqVO request) {
        memberEmailAuthService.sendCode(request.getEmail(), request.getScene(), getClientIP());
        return success(true);
    }

    @PostMapping("/email-register")
    @Operation(summary = "邮箱验证码独立注册，密码选填")
    @ApiAccessLog(sanitizeKeys = {"password"})
    @PermitAll
    public CommonResult<AppMemberAuthLoginRespVO> register(
            @RequestBody @Valid AppMemberEmailRegisterReqVO request,
            @RequestHeader(value = EMAIL_CODE_HEADER, required = false) String code) {
        return success(memberEmailAuthService.register(request.getEmail(), request.getPassword(), code, getClientIP()));
    }

    @PostMapping("/email-login")
    @Operation(summary = "已有邮箱验证码登录，不自动注册")
    @PermitAll
    public CommonResult<AppMemberAuthLoginRespVO> login(
            @RequestBody @Valid AppMemberEmailLoginReqVO request,
            @RequestHeader(value = EMAIL_CODE_HEADER, required = false) String code) {
        return success(memberEmailAuthService.login(request.getEmail(), code, getClientIP()));
    }

    @PostMapping("/email-password-login")
    @Operation(summary = "邮箱和密码登录")
    @ApiAccessLog(sanitizeKeys = {"password"})
    @PermitAll
    public CommonResult<AppMemberAuthLoginRespVO> passwordLogin(
            @RequestBody @Valid AppMemberEmailPasswordLoginReqVO request) {
        return success(memberEmailAuthService.passwordLogin(request.getEmail(), request.getPassword(), getClientIP()));
    }

    @PostMapping("/send-email-first-password-code")
    @Operation(summary = "当前邮箱会员首次设密获取验证码")
    public CommonResult<Boolean> sendFirstPasswordCode() {
        memberEmailAuthService.sendFirstPasswordCode(requireMember(), getClientIP());
        return success(true);
    }

    @PostMapping("/email-first-password")
    @Operation(summary = "当前邮箱会员首次设置密码，不覆盖已设密码")
    @ApiAccessLog(sanitizeKeys = {"password"})
    public CommonResult<Boolean> setFirstPassword(
            @RequestBody @Valid AppMemberFirstPasswordReqVO request,
            @RequestHeader(value = EMAIL_CODE_HEADER, required = false) String code) {
        memberEmailAuthService.setFirstPassword(requireMember(), request.getPassword(), code, getClientIP());
        return success(true);
    }
}
