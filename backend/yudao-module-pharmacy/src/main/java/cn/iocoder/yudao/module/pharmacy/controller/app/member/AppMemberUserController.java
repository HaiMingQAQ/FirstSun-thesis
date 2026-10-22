package cn.iocoder.yudao.module.pharmacy.controller.app.member;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.pharmacy.controller.app.member.vo.user.AppMemberUserInfoRespVO;
import cn.iocoder.yudao.module.pharmacy.controller.app.member.vo.user.AppMemberUserUpdateReqVO;
import cn.iocoder.yudao.module.pharmacy.controller.app.member.vo.auth.AppMemberFirstPasswordReqVO;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.MemberLevelDO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.MemberUserDO;
import cn.iocoder.yudao.module.pharmacy.service.member.MemberLevelService;
import cn.iocoder.yudao.module.pharmacy.service.member.MemberUserService;
import cn.iocoder.yudao.module.pharmacy.service.member.MemberAuthService;
import cn.iocoder.yudao.module.pharmacy.service.member.MemberAvatarService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.pharmacy.service.member.AppMemberAccess.requireMember;

/**
 * 用户 APP - 会员个人中心
 *
 * 所有查询与修改均基于登录令牌中的会员编号，不接受前端传入会员编号。
 */
@Tag(name = "用户 APP - 会员个人中心")
@RestController
@RequestMapping("/member/user")
@Validated
@Slf4j
public class AppMemberUserController {

    @Resource
    private MemberUserService memberUserService;

    @Resource
    private MemberLevelService memberLevelService;

    @Resource
    private MemberAuthService memberAuthService;

    @Resource
    private MemberAvatarService memberAvatarService;

    @GetMapping("/get")
    @Operation(summary = "获得会员基本信息")
    public CommonResult<AppMemberUserInfoRespVO> getUserInfo() {
        MemberUserDO user = memberUserService.validateMemberUserExists(requireMember());
        AppMemberUserInfoRespVO respVO = BeanUtils.toBean(user, AppMemberUserInfoRespVO.class);
        respVO.setPasswordSet(memberUserService.isPasswordSet(user.getPassword()));
        // 补充会员等级名称
        if (user.getLevelId() != null) {
            MemberLevelDO level = memberLevelService.getMemberLevel(user.getLevelId());
            if (level != null) {
                respVO.setLevelName(level.getName());
            }
        }
        return success(respVO);
    }

    @PutMapping("/update")
    @Operation(summary = "修改会员基本信息")
    public CommonResult<Boolean> updateUserInfo(@RequestBody @Valid AppMemberUserUpdateReqVO reqVO) {
        Long userId = requireMember();
        if (reqVO.isEmailSpecified()) {
            memberUserService.updateMemberUserProfile(userId,
                    reqVO.getNickname(), reqVO.getAvatar(), reqVO.getSex(), reqVO.getEmail());
        } else {
            memberUserService.updateMemberUserProfile(userId,
                    reqVO.getNickname(), reqVO.getAvatar(), reqVO.getSex());
        }
        return success(true);
    }

    @PostMapping("/send-password-code")
    @Operation(summary = "为当前会员首次设置密码获取验证码")
    public CommonResult<Boolean> sendPasswordCode() {
        memberAuthService.sendFirstPasswordCode(requireMember());
        return success(true);
    }

    @PostMapping("/set-password")
    @Operation(summary = "当前会员首次设置密码（不能覆盖已设置密码）")
    @ApiAccessLog(sanitizeKeys = {"password"})
    public CommonResult<Boolean> setPassword(
            @RequestBody @Valid AppMemberFirstPasswordReqVO reqVO,
            @RequestHeader(value = "X-Sms-Code", required = false) String code) {
        memberAuthService.setFirstPassword(requireMember(), reqVO.getPassword(), code);
        return success(true);
    }

    @PostMapping("/avatar")
    @Operation(summary = "上传当前会员头像")
    public CommonResult<String> uploadAvatar(@RequestParam("file") MultipartFile file)
            throws Exception {
        return success(memberAvatarService.upload(requireMember(), file));
    }

}
