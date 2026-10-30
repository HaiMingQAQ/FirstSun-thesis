package cn.iocoder.yudao.module.pharmacy.service.member;

import java.util.Locale;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.pharmacy.enums.member.MemberEmailErrorCodes.EMAIL_INVALID;

/** 邮箱登录身份使用统一的去空格、小写形式，不做供应商特有别名合并。 */
public final class MemberEmailAddress {

    private static final Pattern ADDRESS = Pattern.compile(
            "^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+"
                    + "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");

    private MemberEmailAddress() {
    }

    public static String normalize(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        int at = normalized.indexOf('@');
        if (normalized.length() > 254 || at < 1 || at > 64 || !ADDRESS.matcher(normalized).matches()
                || normalized.startsWith(".") || normalized.charAt(at - 1) == '.'
                || normalized.substring(0, at).contains("..")) {
            throw exception(EMAIL_INVALID);
        }
        return normalized;
    }
}
