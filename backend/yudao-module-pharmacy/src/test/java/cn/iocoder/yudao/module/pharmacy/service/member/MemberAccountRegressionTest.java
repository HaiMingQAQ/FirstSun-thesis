package cn.iocoder.yudao.module.pharmacy.service.member;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

class MemberAccountRegressionTest {
    @Test
    void legacyEmptyPasswordHashMustNotAllowEmptyPasswordLogin() {
        var service = new MemberUserServiceImpl();
        String legacy = new BCryptPasswordEncoder().encode("");
        assertFalse(service.isPasswordMatch("", legacy));
    }
}
