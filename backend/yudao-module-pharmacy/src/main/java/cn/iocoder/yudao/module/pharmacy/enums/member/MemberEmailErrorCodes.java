package cn.iocoder.yudao.module.pharmacy.enums.member;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/** 邮箱身份与一次性验证码错误码；不包含供应商正文或请求内容。 */
public interface MemberEmailErrorCodes {

    ErrorCode EMAIL_INVALID = new ErrorCode(1_030_006_020, "邮箱格式不正确");
    ErrorCode EMAIL_UNAVAILABLE = new ErrorCode(1_030_006_021, "邮箱认证暂不可用");
    ErrorCode EMAIL_SCENE_INVALID = new ErrorCode(1_030_006_022, "邮箱验证码用途不正确");
    ErrorCode EMAIL_SEND_TOO_FAST = new ErrorCode(1_030_006_023, "请求过于频繁，请稍后再试");
    ErrorCode EMAIL_SEND_FAILED = new ErrorCode(1_030_006_024, "邮件发送失败，请稍后重试");
    ErrorCode EMAIL_CODE_INVALID = new ErrorCode(1_030_006_025, "邮箱验证码错误或已失效，请重新获取");
    ErrorCode EMAIL_REGISTER_EXISTS = new ErrorCode(1_030_006_026, "该邮箱已有账号，请登录");
    ErrorCode EMAIL_LOGIN_BAD_CREDENTIALS = new ErrorCode(1_030_006_027, "邮箱或密码错误");
    ErrorCode EMAIL_IDENTITY_MISSING = new ErrorCode(1_030_006_028, "未找到已验证的邮箱账号");
    ErrorCode EMAIL_TEST_RECIPIENT_ONLY = new ErrorCode(1_030_006_029, "当前测试环境仅向配置的测试邮箱发送验证码");
    ErrorCode EMAIL_OLD_PASSWORD_INVALID = new ErrorCode(1_030_006_030, "原密码不正确");
    ErrorCode EMAIL_PASSWORD_STATE_CHANGED = new ErrorCode(1_030_006_031, "账号状态已变化，请重新登录或重新获取验证码");
    ErrorCode EMAIL_PASSWORD_NOT_SET = new ErrorCode(1_030_006_032, "尚未设置密码，请使用首次设密");
}
