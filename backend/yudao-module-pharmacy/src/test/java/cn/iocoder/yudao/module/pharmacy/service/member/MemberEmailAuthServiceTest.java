package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenRespDTO;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.MemberEmailIdentityDO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.MemberUserDO;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.member.MemberEmailIdentityMapper;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.member.MemberUserMapper;
import cn.iocoder.yudao.module.system.api.logger.LoginLogApi;
import cn.iocoder.yudao.module.system.enums.oauth2.OAuth2ClientConstants;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static cn.iocoder.yudao.module.pharmacy.enums.ErrorCodeConstants.*;
import static cn.iocoder.yudao.module.pharmacy.enums.member.MemberEmailErrorCodes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemberEmailAuthServiceTest {
    final MemberUserMapper mapper = mock(MemberUserMapper.class);
    final MemberEmailIdentityMapper identities = mock(MemberEmailIdentityMapper.class);
    final MemberUserServiceImpl users = new MemberUserServiceImpl();
    final MemberEmailCodeService codes = mock(MemberEmailCodeService.class);
    final OAuth2TokenCommonApi tokens = mock(OAuth2TokenCommonApi.class);
    final MemberEmailAuthServiceImpl auth = new MemberEmailAuthServiceImpl();
    final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    static final String EMAIL = "member@example.test";
    static final String IP = "127.0.0.1";

    @BeforeEach void setup() {
        TenantContextHolder.setTenantId(7L);
        ReflectionTestUtils.setField(users, "memberUserMapper", mapper);
        ReflectionTestUtils.setField(auth, "memberUserService", users);
        ReflectionTestUtils.setField(auth, "memberEmailIdentityMapper", identities);
        ReflectionTestUtils.setField(auth, "memberEmailCodeService", codes);
        ReflectionTestUtils.setField(auth, "oauth2TokenApi", tokens);
        ReflectionTestUtils.setField(auth, "loginLogApi", mock(LoginLogApi.class));
        when(codes.isEnabled()).thenReturn(true);
        when(mapper.insert(any(MemberUserDO.class))).thenAnswer(i -> { i.<MemberUserDO>getArgument(0).setId(9L); return 1; });
        when(tokens.createAccessToken(any())).thenAnswer(i -> {
            var request = i.<cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenCreateReqDTO>getArgument(0);
            assertEquals(9L, request.getUserId());
            assertEquals(UserTypeEnum.MEMBER.getValue(), request.getUserType());
            assertEquals(OAuth2ClientConstants.CLIENT_ID_DEFAULT, request.getClientId());
            var response = new OAuth2AccessTokenRespDTO(); response.setUserId(9L); response.setAccessToken("unit-only"); return response;
        });
    }
    @AfterEach void clean() { TenantContextHolder.clear(); }

    MemberUserDO own(String password) {
        var user = new MemberUserDO(); user.setId(9L); user.setTenantId(7L); user.setStatus(0); user.setPassword(password);
        var identity = new MemberEmailIdentityDO(); identity.setId(19L); identity.setTenantId(7L); identity.setMemberId(9L); identity.setNormalizedEmail(EMAIL);
        when(identities.selectByEmail(7L, EMAIL)).thenReturn(identity);
        when(identities.selectByMember(7L, 9L)).thenReturn(identity);
        when(mapper.selectByIdAndTenantId(9L, 7L)).thenReturn(user);
        return user;
    }

    @Test void registrationConsumesCodeBeforeCreatingPhoneLessMemberAndVerifiedIdentity() {
        assertEquals("unit-only", auth.register(" Member@Example.Test ", " secret12 ", "123456", IP).getAccessToken());
        var order = inOrder(codes, mapper, identities, tokens);
        order.verify(codes).ensureAvailable();
        order.verify(codes).verifyAndConsume(EMAIL, "register", "123456", IP);
        order.verify(identities).selectByEmail(7L, EMAIL);
        var capture = ArgumentCaptor.forClass(MemberUserDO.class);
        order.verify(mapper).insert(capture.capture());
        var user = capture.getValue();
        assertNull(user.getMobile()); assertEquals(7L, user.getTenantId()); assertEquals(0, user.getStatus());
        assertEquals(0, user.getPoint()); assertEquals(0, user.getExperience()); assertEquals("", user.getAvatar());
        assertEquals(IP, user.getRegisterIp()); assertTrue(encoder.matches(" secret12 ", user.getPassword()));
        var binding = ArgumentCaptor.forClass(MemberEmailIdentityDO.class);
        order.verify(identities).insert(binding.capture());
        assertEquals(EMAIL, binding.getValue().getNormalizedEmail()); assertEquals(9L, binding.getValue().getMemberId());
        assertEquals(7L, binding.getValue().getTenantId()); order.verify(tokens).createAccessToken(any());
        verify(mapper, never()).selectByMobile(any());
    }
    @Test void optionalPasswordStaysUnsetAndOldContactEmailIsNeverLookedUpOrPatched() {
        auth.register(EMAIL, null, "123456", IP);
        var capture = ArgumentCaptor.forClass(MemberUserDO.class); verify(mapper).insert(capture.capture());
        assertFalse(users.isPasswordSet(capture.getValue().getPassword()));
        verify(mapper, never()).selectByMobile(any());
        verify(mapper, never()).updateProfileEmail(anyLong(), anyLong(), any());
    }
    @Test void badCodeCreatesNoMemberIdentityOrToken() {
        doThrow(new ServiceException(EMAIL_CODE_INVALID)).when(codes).verifyAndConsume(anyString(), anyString(), any(), any());
        assertThrows(ServiceException.class, () -> auth.register(EMAIL, null, "bad", IP));
        verifyNoInteractions(mapper, identities, tokens);
    }
    @Test void existingIdentityAndConcurrentUniqueCollisionCannotOverwriteOrIssueToken() {
        own(encoder.encode("old12345"));
        var duplicate = assertThrows(ServiceException.class, () -> auth.register(EMAIL, "new12345", "123456", IP));
        assertEquals(EMAIL_REGISTER_EXISTS.getCode(), duplicate.getCode());
        verify(mapper, never()).insert(any(MemberUserDO.class)); verifyNoInteractions(tokens);
        reset(identities);
        when(identities.insert(any(MemberEmailIdentityDO.class))).thenThrow(new DuplicateKeyException("unit"));
        assertEquals(EMAIL_REGISTER_EXISTS.getCode(), assertThrows(ServiceException.class,
                () -> auth.register(EMAIL, null, "123456", IP)).getCode());
        verifyNoInteractions(tokens);
    }
    @Test void codeLoginNeverAutoRegistersUnknownOrContactOnlyEmail() {
        assertEquals(EMAIL_LOGIN_BAD_CREDENTIALS.getCode(), assertThrows(ServiceException.class,
                () -> auth.login(EMAIL, "123456", IP)).getCode());
        verify(codes).verifyAndConsume(EMAIL, "login", "123456", IP);
        verifyNoInteractions(mapper, tokens);
    }
    @Test void codeLoginAndPasswordLoginUseOnlyVerifiedIdentity() {
        own(encoder.encode("short"));
        assertNotNull(auth.login(EMAIL, "123456", IP).getAccessToken());
        assertNotNull(auth.passwordLogin(" MEMBER@example.test ", "short", IP).getAccessToken());
        verify(codes).verifyAndConsume(EMAIL, "login", "123456", IP);
        verify(mapper, times(2)).selectByIdAndTenantId(9L, 7L);
    }
    @Test void disabledMissingAndWrongPasswordNeverIssueToken() {
        var user = own(encoder.encode("secret12")); user.setStatus(1);
        assertEquals(PHARMACY_MEMBER_USER_DISABLED.getCode(), assertThrows(ServiceException.class,
                () -> auth.login(EMAIL, "123456", IP)).getCode());
        user.setStatus(0);
        assertEquals(EMAIL_LOGIN_BAD_CREDENTIALS.getCode(), assertThrows(ServiceException.class,
                () -> auth.passwordLogin(EMAIL, "wrong", IP)).getCode());
        when(mapper.selectByIdAndTenantId(9L, 7L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> auth.passwordLogin(EMAIL, "secret12", IP));
        verifyNoInteractions(tokens);
    }
    @Test void disabledFeatureBlocksEvenPasswordLoginBeforeIdentityLookup() {
        doThrow(new ServiceException(EMAIL_UNAVAILABLE)).when(codes).ensureAvailable();
        assertEquals(EMAIL_UNAVAILABLE.getCode(), assertThrows(ServiceException.class,
                () -> auth.passwordLogin(EMAIL, "secret12", IP)).getCode());
        verifyNoInteractions(identities, mapper, tokens);
    }
    @Test void passwordAttemptLimitRunsBeforeIdentityLookupOrPasswordCheck() {
        doThrow(new ServiceException(EMAIL_SEND_TOO_FAST)).when(codes).checkPasswordLoginAttempt(EMAIL, IP);
        assertThrows(ServiceException.class, () -> auth.passwordLogin(EMAIL, "secret12", IP));
        verifyNoInteractions(identities, mapper, tokens);
    }
    @Test void disabledFeatureKeepsOldProfileReadableWithoutNewIdentityTable() {
        when(codes.isEnabled()).thenReturn(false);
        assertNull(auth.getLoginEmail(9L));
        verifyNoInteractions(identities, mapper, tokens);
    }
    @Test void firstPasswordUsesOwnIdentityRatherThanEditableContactAndAtomicHash() {
        var user = own(encoder.encode("")); user.setEmail("unverified@example.test");
        when(mapper.updateInitialEmailPassword(eq(9L), eq(7L), eq(user.getPassword()), anyString(), eq(19L), eq(EMAIL))).thenReturn(1);
        auth.sendFirstPasswordCode(9L, IP); auth.setFirstPassword(9L, "new12345", "123456", IP);
        verify(codes).sendCode(EMAIL, "first-password", IP);
        verify(codes).verifyAndConsume(EMAIL, "first-password", "123456", IP);
        var hash = ArgumentCaptor.forClass(String.class);
        verify(mapper).updateInitialEmailPassword(eq(9L), eq(7L), eq(user.getPassword()), hash.capture(), eq(19L), eq(EMAIL));
        assertTrue(encoder.matches("new12345", hash.getValue())); verifyNoInteractions(tokens);
    }
    @Test void firstPasswordCannotOverwriteAndWrongCodeCannotWrite() {
        var user = own("");
        assertEquals(PHARMACY_MEMBER_PASSWORD_ALREADY_SET.getCode(), assertThrows(ServiceException.class,
                () -> auth.setFirstPassword(9L, "new12345", "123456", IP)).getCode());
        doThrow(new ServiceException(EMAIL_CODE_INVALID)).when(codes).verifyAndConsume(anyString(), anyString(), any(), any());
        assertThrows(ServiceException.class, () -> auth.setFirstPassword(9L, "new12345", "bad", IP));
        verify(mapper, times(1)).updateInitialEmailPassword(anyLong(), anyLong(), any(), anyString(), anyLong(), anyString());
        user.setPassword(encoder.encode("existing12")); reset(codes);
        assertThrows(ServiceException.class, () -> auth.sendFirstPasswordCode(9L, IP));
        verify(codes, never()).sendCode(anyString(), anyString(), any());
    }
    @Test void tenantIsolationAndPublicSceneRestriction() {
        own(""); TenantContextHolder.setTenantId(8L);
        assertThrows(ServiceException.class, () -> auth.login(EMAIL, "123456", IP));
        assertNull(auth.getLoginEmail(9L)); verifyNoInteractions(tokens);
        assertEquals(EMAIL_SCENE_INVALID.getCode(), assertThrows(ServiceException.class,
                () -> auth.sendCode(EMAIL, "first-password", IP)).getCode());
        verify(codes, never()).sendCode(anyString(), anyString(), any());
    }
}
