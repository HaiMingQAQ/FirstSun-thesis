package cn.iocoder.yudao.module.pharmacy.service.member;

/** 已验证邮箱身份的验证码，只允许约定用途。 */
public interface MemberEmailCodeService {

    String SCENE_REGISTER = "register";
    String SCENE_LOGIN = "login";
    String SCENE_FIRST_PASSWORD = "first-password";

    /** 只读开关，关闭时旧实例无需查询新增身份表。 */
    boolean isEnabled();

    /** 所有邮箱认证写入口共用的默认关闭门禁；不依赖验证码消费。 */
    void ensureAvailable();

    /** 邮箱密码入口的邮箱/IP有限尝试窗口，不消费验证码。 */
    void checkPasswordLoginAttempt(String email, String clientIp);

    void sendCode(String email, String scene, String clientIp);

    void verifyAndConsume(String email, String scene, String code, String clientIp);
}
