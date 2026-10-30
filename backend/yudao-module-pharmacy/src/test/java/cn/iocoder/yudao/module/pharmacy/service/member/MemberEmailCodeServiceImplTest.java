package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.config.MemberEmailProperties;
import cn.iocoder.yudao.module.pharmacy.dal.redis.member.MemberEmailCodeRedisDAO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static cn.iocoder.yudao.module.pharmacy.enums.member.MemberEmailErrorCodes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemberEmailCodeServiceImplTest {

    private final MemberEmailProperties properties = new MemberEmailProperties();
    private final MemberEmailCodeRedisDAO redis = mock(MemberEmailCodeRedisDAO.class);
    private final ResendEmailSender sender = mock(ResendEmailSender.class);
    private final MemberEmailCodeServiceImpl service = new MemberEmailCodeServiceImpl(properties, redis, sender);

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(1L);
        properties.setEnabled(true);
        properties.setFrom("sender@example.com");
        properties.setApiKey("offline-test-key");
    }

    @AfterEach
    void cleanUp() {
        TenantContextHolder.clear();
    }

    @Test
    void normalizeRetainsAliasesAndRejectsInvalidAddresses() {
        assertEquals("user+tag@example.com", MemberEmailAddress.normalize(" User+Tag@Example.COM "));
        for (String invalid : new String[]{"", "x", "x@y", ".x@example.com", "x..y@example.com",
                "x.@example.com", "x@-example.com", "x@y..com", "x y@example.com", "用户@example.com",
                "x\n@example.com", "x".repeat(65) + "@example.com"}) {
            assertEquals(EMAIL_INVALID.getCode(), assertThrows(ServiceException.class,
                    () -> MemberEmailAddress.normalize(invalid)).getCode());
        }
    }

    @Test
    void disabledAndIncompleteConfigurationNeverTouchesRedisOrSender() {
        properties.setEnabled(false);
        assertCode(EMAIL_UNAVAILABLE.getCode(), () -> service.sendCode("a@example.com", "login", "ip"));
        properties.setEnabled(true);
        properties.setApiKey("");
        assertCode(EMAIL_UNAVAILABLE.getCode(), service::ensureAvailable);
        properties.setApiKey("offline-test-key");
        properties.setFrom("invalid");
        assertCode(EMAIL_UNAVAILABLE.getCode(), service::ensureAvailable);
        verifyNoInteractions(redis, sender);
        assertFalse(new MemberEmailProperties().isEnabled());
        assertFalse(properties.toString().contains("offline-test-key"));
    }

    @Test
    void unknownPurposeAndTestRecipientMismatchNeverSend() {
        assertCode(EMAIL_SCENE_INVALID.getCode(), () -> service.sendCode("a@example.com", "reset", "ip"));
        properties.setTestRecipient("owner@example.com");
        assertCode(EMAIL_TEST_RECIPIENT_ONLY.getCode(), () -> service.sendCode("other@example.com", "login", "ip"));
        verifyNoInteractions(redis, sender);
    }

    @Test
    void sendCreatesPendingDigestBeforeSendingAndActivatesOnlyAfterSuccess() {
        when(redis.beginSend(eq(1L), eq("a@example.com"), eq("register"), eq("ip"), anyString(), anyString()))
                .thenReturn(true);
        when(redis.activate(eq(1L), eq("a@example.com"), eq("register"), anyString())).thenReturn(true);
        service.sendCode(" A@Example.COM ", "register", "ip");

        ArgumentCaptor<String> version = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> digest = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        var order = inOrder(redis, sender);
        order.verify(redis).beginSend(eq(1L), eq("a@example.com"), eq("register"), eq("ip"),
                version.capture(), digest.capture());
        order.verify(sender).sendCode(eq("a@example.com"), eq("register"), code.capture());
        order.verify(redis).activate(1L, "a@example.com", "register", version.getValue());
        assertTrue(code.getValue().matches("[0-9]{6}"));
        assertEquals(64, digest.getValue().length());
        assertNotEquals(code.getValue(), digest.getValue());
    }

    @Test
    void failedSendAbortsOnlyItsPendingVersionAndNeverActivates() {
        when(redis.beginSend(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        doThrow(new IllegalStateException("supplier-body-must-not-escape")).when(sender).sendCode(anyString(), anyString(), anyString());
        var ex = assertThrows(ServiceException.class, () -> service.sendCode("a@example.com", "login", "ip"));
        assertEquals(EMAIL_SEND_FAILED.getCode(), ex.getCode());
        assertFalse(ex.toString().contains("supplier-body"));
        verify(redis, never()).activate(anyLong(), anyString(), anyString(), anyString());
        verify(redis).abort(eq(1L), eq("a@example.com"), eq("login"), anyString());
    }

    @Test
    void delayedSuccessfulSendCannotActivateSupersededVersion() {
        when(redis.beginSend(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        when(redis.activate(anyLong(), anyString(), anyString(), anyString())).thenReturn(false);
        assertCode(EMAIL_SEND_FAILED.getCode(), () -> service.sendCode("a@example.com", "login", "ip"));
        verify(redis).abort(eq(1L), eq("a@example.com"), eq("login"), anyString());
    }

    @Test
    void rateLimitsStopSendAndVerificationBeforeLookingUpCodes() {
        assertCode(EMAIL_SEND_TOO_FAST.getCode(), () -> service.sendCode("a@example.com", "login", "ip"));
        assertCode(EMAIL_SEND_TOO_FAST.getCode(), () -> service.verifyAndConsume("a@example.com", "login", "123456", "ip"));
        verifyNoInteractions(sender);
        verify(redis, never()).getActiveVersion(anyLong(), anyString(), anyString());
    }

    @Test
    void consumePassesCurrentVersionAndRejectsRepeatOrChangedVersion() {
        when(redis.allowVerify(1L, "ip")).thenReturn(true);
        when(redis.getActiveVersion(1L, "a@example.com", "login")).thenReturn("v1");
        when(redis.consume(eq(1L), eq("a@example.com"), eq("login"), eq("v1"), anyString()))
                .thenReturn(true, false);
        service.verifyAndConsume("a@example.com", "login", "123456", "ip");
        assertCode(EMAIL_CODE_INVALID.getCode(), () -> service.verifyAndConsume("a@example.com", "login", "123456", "ip"));
        verify(redis, times(2)).consume(eq(1L), eq("a@example.com"), eq("login"), eq("v1"), argThat(v -> v.length() == 64));
    }

    @Test
    void purposeEmailAndTenantArePassedToEveryConsumption() {
        TenantContextHolder.setTenantId(9L);
        when(redis.allowVerify(9L, "ip")).thenReturn(true);
        assertCode(EMAIL_CODE_INVALID.getCode(), () -> service.verifyAndConsume("other@example.com", "first-password", "123456", "ip"));
        verify(redis).getActiveVersion(9L, "other@example.com", "first-password");
        verify(redis, never()).consume(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void passwordAttemptLimitIsIndependentAndUsesNormalizedEmailAndTenant() {
        when(redis.allowPasswordLogin(1L, "a@example.com", "ip")).thenReturn(true, false);
        service.checkPasswordLoginAttempt(" A@Example.COM ", "ip");
        assertCode(EMAIL_SEND_TOO_FAST.getCode(), () -> service.checkPasswordLoginAttempt("a@example.com", "ip"));
        verify(redis, times(2)).allowPasswordLogin(1L, "a@example.com", "ip");
        verify(redis, never()).consume(anyLong(), anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(sender);
    }

    @Test
    void redisFailuresAreBusinessErrorsWithoutOriginalCause() {
        when(redis.allowVerify(1L, "ip")).thenThrow(new IllegalStateException("private-redis-details"));
        var ex = assertThrows(ServiceException.class,
                () -> service.verifyAndConsume("a@example.com", "login", "123456", "ip"));
        assertEquals(EMAIL_UNAVAILABLE.getCode(), ex.getCode());
        assertNull(ex.getCause());
        assertFalse(ex.toString().contains("private-redis-details"));
    }

    @Test
    void resendDevRequiresExplicitOwnerRecipientBeforeAnyRequest() {
        properties.setFrom("onboarding@resend.dev");
        assertCode(EMAIL_UNAVAILABLE.getCode(), () -> service.sendCode("a@example.com", "login", "ip"));
        verifyNoInteractions(redis, sender);
    }

    private static void assertCode(int expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(ServiceException.class, action).getCode());
    }
}
