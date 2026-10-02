package cn.iocoder.yudao.framework.apilog.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PrivateBusinessLogPathTest {
    @Test void consultationLogsAreRedactedWithoutAControllerAnnotation() {
        assertTrue(ApiAccessLogSanitizer.isPrivateBusinessPath("/app-api/pharmacy/ai/consult"));
        assertTrue(ApiAccessLogSanitizer.isPrivateBusinessPath("/app-api/pharmacy/ai/consult/"));
        assertTrue(ApiAccessLogSanitizer.isPrivateBusinessPath("/app-api/pharmacy/ai/consult/stream"));
        assertTrue(ApiAccessLogSanitizer.isPrivateBusinessPath("/app-api/pharmacy/ai/topics/1"));
        assertTrue(ApiAccessLogSanitizer.isPrivateBusinessPath("/app-api/pharmacy/ai/consult/stream;foo=1"));
        assertTrue(ApiAccessLogSanitizer.isPrivateBusinessPath("/app-api/pharmacy/ai/consult;foo=1"));
        assertTrue(ApiAccessLogSanitizer.isPrivateBusinessPath("/app-api;foo=1/pharmacy/ai/consult"));
        assertTrue(ApiAccessLogSanitizer.isPrivateBusinessPath("/app-api/member/prescription;foo=1/material/upload"));
        assertTrue(ApiAccessLogSanitizer.isPrivateBusinessPath("/admin-api/pharmacy/prescription/material/get"));
        assertTrue(ApiAccessLogSanitizer.isPrivateBusinessPath("/app-api/member/consultation;foo=1/send"));
        assertTrue(ApiAccessLogSanitizer.isPrivateBusinessPath("/admin-api/pharmacy/consultation/messages"));
        assertFalse(ApiAccessLogSanitizer.isPrivateBusinessPath("/app-api/pharmacy/drug/page"));
        assertFalse(ApiAccessLogSanitizer.isPrivateBusinessPath(null));
        assertTrue(ApiAccessLogSanitizer.isAuthenticationPath("/app-api/member/auth/login-or-register"));
    }
}
