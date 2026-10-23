package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenCreateReqDTO;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.controller.app.member.vo.auth.AppMemberAuthLoginRespVO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.MemberEmailIdentityDO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.MemberUserDO;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.member.MemberEmailIdentityMapper;
import cn.iocoder.yudao.module.system.api.logger.LoginLogApi;
import cn.iocoder.yudao.module.system.api.logger.dto.LoginLogCreateReqDTO;
import cn.iocoder.yudao.module.system.enums.logger.LoginLogTypeEnum;
import cn.iocoder.yudao.module.system.enums.logger.LoginResultEnum;
import cn.iocoder.yudao.module.system.enums.oauth2.OAuth2ClientConstants;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.monitor.TracerUtils.getTraceId;
import static cn.iocoder.yudao.framework.common.util.servlet.ServletUtils.getUserAgent;
import static cn.iocoder.yudao.module.pharmacy.enums.ErrorCodeConstants.*;
import static cn.iocoder.yudao.module.pharmacy.enums.member.MemberEmailErrorCodes.*;
import static cn.iocoder.yudao.module.pharmacy.service.member.MemberEmailCodeService.*;

@Service
@Validated
public class MemberEmailAuthServiceImpl implements MemberEmailAuthService {
    @Resource
    private MemberUserService memberUserService;
    @Resource
    private MemberEmailIdentityMapper memberEmailIdentityMapper;
    @Resource
    private MemberEmailCodeService memberEmailCodeService;
    @Resource
    private OAuth2TokenCommonApi oauth2TokenApi;
    @Resource
    private LoginLogApi loginLogApi;

    @Override
    public void sendCode(String email, String scene, String clientIp) {
        memberEmailCodeService.ensureAvailable();
        if (!SCENE_REGISTER.equals(scene) && !SCENE_LOGIN.equals(scene)) {
            throw exception(EMAIL_SCENE_INVALID);
        }
        memberEmailCodeService.sendCode(MemberEmailAddress.normalize(email), scene, clientIp);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AppMemberAuthLoginRespVO register(String email, String password, String code, String clientIp) {
        memberEmailCodeService.ensureAvailable();
        String normalized = MemberEmailAddress.normalize(email);
        if (password != null && !password.isEmpty()) {
            MemberPasswordPolicy.validate(password);
        }
        memberEmailCodeService.verifyAndConsume(normalized, SCENE_REGISTER, code, clientIp);
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        if (memberEmailIdentityMapper.selectByEmail(tenantId, normalized) != null) {
            throw exception(EMAIL_REGISTER_EXISTS);
        }
        MemberUserDO member;
        try {
            member = memberUserService.createEmailMember(tenantId, normalized, password, clientIp);
            var identity = new MemberEmailIdentityDO();
            identity.setTenantId(tenantId);
            identity.setNormalizedEmail(normalized);
            identity.setMemberId(member.getId());
            memberEmailIdentityMapper.insert(identity);
        } catch (DuplicateKeyException ex) {
            // 抛出业务异常，由事务同时回滚候选会员和绑定；不覆盖已有身份。
            throw exception(EMAIL_REGISTER_EXISTS);
        }
        return createToken(member, normalized, clientIp);
    }

    @Override
    public AppMemberAuthLoginRespVO login(String email, String code, String clientIp) {
        memberEmailCodeService.ensureAvailable();
        String normalized = MemberEmailAddress.normalize(email);
        memberEmailCodeService.verifyAndConsume(normalized, SCENE_LOGIN, code, clientIp);
        return createToken(requireEmailMember(normalized, clientIp), normalized, clientIp);
    }

    @Override
    public AppMemberAuthLoginRespVO passwordLogin(String email, String password, String clientIp) {
        memberEmailCodeService.ensureAvailable();
        String normalized = MemberEmailAddress.normalize(email);
        memberEmailCodeService.checkPasswordLoginAttempt(normalized, clientIp);
        MemberUserDO member = requireEmailMember(normalized, clientIp);
        if (!memberUserService.isPasswordMatch(password, member.getPassword())) {
            logLogin(member.getId(), normalized, clientIp, LoginResultEnum.BAD_CREDENTIALS);
            throw exception(EMAIL_LOGIN_BAD_CREDENTIALS);
        }
        return createToken(member, normalized, clientIp);
    }

    @Override
    public String getLoginEmail(Long memberId) {
        // 默认关闭时兼容尚未执行新迁移的旧数据库，不影响原会员资料读取。
        if (!memberEmailCodeService.isEnabled()) {
            return null;
        }
        MemberEmailIdentityDO identity = memberEmailIdentityMapper.selectByMember(
                TenantContextHolder.getRequiredTenantId(), memberId);
        return identity == null ? null : identity.getNormalizedEmail();
    }

    @Override
    public void sendFirstPasswordCode(Long memberId, String clientIp) {
        memberEmailCodeService.ensureAvailable();
        requireUnsetPassword(memberId);
        MemberEmailIdentityDO identity = requireOwnIdentity(memberId);
        memberEmailCodeService.sendCode(identity.getNormalizedEmail(), SCENE_FIRST_PASSWORD, clientIp);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setFirstPassword(Long memberId, String password, String code, String clientIp) {
        memberEmailCodeService.ensureAvailable();
        MemberPasswordPolicy.validate(password);
        MemberUserDO member = requireUnsetPassword(memberId);
        MemberEmailIdentityDO identity = requireOwnIdentity(memberId);
        memberEmailCodeService.verifyAndConsume(identity.getNormalizedEmail(), SCENE_FIRST_PASSWORD, code, clientIp);
        memberUserService.setInitialEmailPassword(member, password, identity.getId(), identity.getNormalizedEmail());
    }

    private MemberEmailIdentityDO requireOwnIdentity(Long memberId) {
        MemberEmailIdentityDO identity = memberEmailIdentityMapper.selectByMember(
                TenantContextHolder.getRequiredTenantId(), memberId);
        if (identity == null) {
            throw exception(EMAIL_IDENTITY_MISSING);
        }
        return identity;
    }

    private MemberUserDO requireUnsetPassword(Long memberId) {
        MemberUserDO member = memberUserService.getMemberUserByIdAndTenantId(
                memberId, TenantContextHolder.getRequiredTenantId());
        if (member == null) {
            throw exception(PHARMACY_MEMBER_USER_NOT_EXISTS);
        }
        if (!Objects.equals(member.getStatus(), 0)) {
            throw exception(PHARMACY_MEMBER_USER_DISABLED);
        }
        if (memberUserService.isPasswordSet(member.getPassword())) {
            throw exception(PHARMACY_MEMBER_PASSWORD_ALREADY_SET);
        }
        return member;
    }

    private MemberUserDO requireEmailMember(String normalized, String clientIp) {
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        MemberEmailIdentityDO identity = memberEmailIdentityMapper.selectByEmail(tenantId, normalized);
        MemberUserDO member = identity == null ? null
                : memberUserService.getMemberUserByIdAndTenantId(identity.getMemberId(), tenantId);
        if (member == null) {
            logLogin(null, normalized, clientIp, LoginResultEnum.BAD_CREDENTIALS);
            throw exception(EMAIL_LOGIN_BAD_CREDENTIALS);
        }
        return member;
    }

    private AppMemberAuthLoginRespVO createToken(MemberUserDO member, String email, String clientIp) {
        if (!Objects.equals(member.getStatus(), 0)) {
            logLogin(member.getId(), email, clientIp, LoginResultEnum.USER_DISABLED);
            throw exception(PHARMACY_MEMBER_USER_DISABLED);
        }
        logLogin(member.getId(), email, clientIp, LoginResultEnum.SUCCESS);
        memberUserService.updateMemberUserLogin(member.getId(), clientIp);
        var token = oauth2TokenApi.createAccessToken(new OAuth2AccessTokenCreateReqDTO()
                .setUserId(member.getId()).setUserType(UserTypeEnum.MEMBER.getValue())
                .setClientId(OAuth2ClientConstants.CLIENT_ID_DEFAULT));
        var response = new AppMemberAuthLoginRespVO();
        response.setUserId(token.getUserId()); response.setAccessToken(token.getAccessToken());
        response.setRefreshToken(token.getRefreshToken()); response.setExpiresTime(token.getExpiresTime());
        return response;
    }

    private void logLogin(Long memberId, String email, String clientIp, LoginResultEnum result) {
        var request = new LoginLogCreateReqDTO();
        request.setLogType(LoginLogTypeEnum.LOGIN_USERNAME.getType()); request.setTraceId(getTraceId());
        request.setUserId(memberId); request.setUserType(UserTypeEnum.MEMBER.getValue());
        // 通用登录日志 username 列较短；邮箱登录身份保存在专表，日志不依赖该字段识别会员。
        request.setUsername(email.substring(0, Math.min(email.length(), 50)));
        request.setUserAgent(getUserAgent()); request.setUserIp(clientIp); request.setResult(result.getResult());
        loginLogApi.createLoginLog(request);
    }
}
