package cn.iocoder.yudao.module.pharmacy.service.member;

import java.nio.charset.StandardCharsets;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.pharmacy.enums.ErrorCodeConstants.PHARMACY_MEMBER_PASSWORD_INVALID;

final class MemberPasswordPolicy {
    private MemberPasswordPolicy() {
    }

    static void validate(String password) {
        if (password == null || password.isBlank()
                || password.codePointCount(0, password.length()) < 8
                || password.codePointCount(0, password.length()) > 64
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw exception(PHARMACY_MEMBER_PASSWORD_INVALID);
        }
    }
}
