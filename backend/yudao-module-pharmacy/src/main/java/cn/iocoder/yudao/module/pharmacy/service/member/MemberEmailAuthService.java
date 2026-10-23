package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.iocoder.yudao.module.pharmacy.controller.app.member.vo.auth.AppMemberAuthLoginRespVO;

/** 独立邮箱认证，不认领历史未验证的联系方式。 */
public interface MemberEmailAuthService {
    void sendCode(String email, String scene, String clientIp);
    AppMemberAuthLoginRespVO register(String email, String password, String code, String clientIp);
    AppMemberAuthLoginRespVO login(String email, String code, String clientIp);
    AppMemberAuthLoginRespVO passwordLogin(String email, String password, String clientIp);
    String getLoginEmail(Long memberId);
    void sendFirstPasswordCode(Long memberId, String clientIp);
    void setFirstPassword(Long memberId, String password, String code, String clientIp);
}
