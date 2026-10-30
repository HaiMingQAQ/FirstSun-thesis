package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenRespDTO;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.controller.app.member.vo.auth.AppMemberRegisterReqVO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.MemberUserDO;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.member.MemberUserMapper;
import cn.iocoder.yudao.module.system.api.logger.LoginLogApi;
import org.junit.jupiter.api.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static cn.iocoder.yudao.module.pharmacy.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemberRegistrationServiceTest {
    final MemberUserMapper mapper = mock(MemberUserMapper.class);
    final MemberUserServiceImpl users = new MemberUserServiceImpl();
    final MemberAuthServiceImpl auth = new MemberAuthServiceImpl();
    final MemberSmsCodeService sms = mock(MemberSmsCodeService.class);
    final OAuth2TokenCommonApi tokens = mock(OAuth2TokenCommonApi.class);
    final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    @BeforeEach void setup() {
        TenantContextHolder.setTenantId(7L);
        ReflectionTestUtils.setField(users, "memberUserMapper", mapper);
        ReflectionTestUtils.setField(auth, "memberUserService", users);
        ReflectionTestUtils.setField(auth, "memberSmsCodeService", sms);
        ReflectionTestUtils.setField(auth, "oauth2TokenApi", tokens);
        ReflectionTestUtils.setField(auth, "loginLogApi", mock(LoginLogApi.class));
        when(tokens.createAccessToken(any())).thenAnswer(i -> {
            var token = new OAuth2AccessTokenRespDTO();
            token.setUserId(9L); token.setAccessToken("unit-only");
            return token;
        });
        when(mapper.insert(any(MemberUserDO.class))).thenAnswer(i -> {i.<MemberUserDO>getArgument(0).setId(9L);return 1;});
    }
    @AfterEach void clean() {TenantContextHolder.clear();}
    AppMemberRegisterReqVO request(String password) {
        var r = new AppMemberRegisterReqVO(); r.setMobile("13900001091");r.setPassword(password);r.setEmail(" a@example.test ");return r;
    }
    @Test void registrationConsumesCodeBeforeInsertAndOnlyNewUserReceivesOptionalFields() {
        auth.register(request(" secret12 "), "123456");
        var order = inOrder(sms, mapper, tokens);
        order.verify(sms).verifyAndConsumeLoginCode("13900001091", "123456");
        var capture = org.mockito.ArgumentCaptor.forClass(MemberUserDO.class);
        order.verify(mapper).selectByMobile("13900001091");order.verify(mapper).insert(capture.capture());
        var u = capture.getValue();assertTrue(encoder.matches(" secret12 ",u.getPassword()));
        assertNotEquals(" secret12 ",u.getPassword());assertEquals("a@example.test",u.getEmail());assertEquals(7L,u.getTenantId());
        assertEquals(0,u.getStatus());assertNotNull(u.getRegisterIp());
        verify(tokens).createAccessToken(any());
    }
    @Test void optionalPasswordRemainsUnset() {
        var u = users.registerMember("13900001091",null,null,null);
        assertFalse(users.isPasswordSet(u.getPassword()));assertFalse(users.isPasswordMatch("",u.getPassword()));
    }
    @Test void badCodeCreatesNoUserAndNoToken() {
        doThrow(new ServiceException(PHARMACY_MEMBER_SMS_CODE_INVALID)).when(sms).verifyAndConsumeLoginCode(anyString(),anyString());
        assertThrows(ServiceException.class,()->auth.register(request(null),"bad"));
        verifyNoInteractions(mapper,tokens);
    }
    @Test void existingMobileIsNotPatched() {
        var u = new MemberUserDO();u.setId(2L);u.setEmail("old@example.test");u.setPassword(encoder.encode("old12345"));
        when(mapper.selectByMobile(anyString())).thenReturn(u);
        var ex=assertThrows(ServiceException.class,()->auth.register(request("new12345"),"123456"));
        assertEquals(PHARMACY_MEMBER_REGISTER_EXISTS.getCode(),ex.getCode());
        assertEquals("old@example.test",u.getEmail());assertTrue(encoder.matches("old12345",u.getPassword()));
        verify(mapper,never()).insert(any(MemberUserDO.class));verify(mapper,never()).updateById(any(MemberUserDO.class));verifyNoInteractions(tokens);
    }
    @Test void concurrentUniqueCollisionBecomesDuplicateBusinessError() {
        when(mapper.insert(any(MemberUserDO.class))).thenThrow(new DuplicateKeyException("unit"));
        var ex=assertThrows(ServiceException.class,()->users.registerMember("13900001091",null,null,null));
        assertEquals(PHARMACY_MEMBER_REGISTER_EXISTS.getCode(),ex.getCode());
    }
    @Test void passwordCharacterAndUtf8Boundaries() {
        for(String p:new String[]{"short","        ","x".repeat(65),"中".repeat(25)})
            assertThrows(ServiceException.class,()->users.registerMember("13900001091",p,null,null));
        assertTrue(users.isPasswordMatch("x".repeat(64),users.registerMember("13900001091","x".repeat(64),null,null).getPassword()));
        assertTrue(users.isPasswordMatch("中".repeat(24),users.registerMember("13900001091","中".repeat(24),null,null).getPassword()));
    }
    @Test void legacyShortNonblankPasswordStillLogsInButDisabledMemberCannot() {
        var u=new MemberUserDO();u.setId(9L);u.setMobile("13900001091");u.setStatus(0);u.setPassword(encoder.encode("123456"));
        when(mapper.selectByMobile(u.getMobile())).thenReturn(u);
        var request=new cn.iocoder.yudao.module.pharmacy.controller.app.member.vo.auth.AppMemberAuthLoginReqVO();
        request.setMobile(u.getMobile());request.setPassword("123456");
        assertNotNull(auth.login(request).getAccessToken());reset(tokens);
        u.setStatus(1);assertThrows(ServiceException.class,()->auth.login(request));verifyNoInteractions(tokens);
    }
    MemberUserDO own() {
        var u=new MemberUserDO();u.setId(9L);u.setStatus(0);u.setMobile("13900001091");u.setPassword(encoder.encode(""));
        when(mapper.selectById(9L)).thenReturn(u);return u;
    }
    @Test void firstPasswordUsesOwnPhoneAndAtomicExpectedHash() {
        var u=own();when(mapper.updateInitialPassword(eq(9L),eq(7L),eq(u.getPassword()),anyString(),eq(u.getMobile()))).thenReturn(1);
        auth.sendFirstPasswordCode(9L);auth.setFirstPassword(9L,"new12345","123456");
        verify(sms).sendLoginCode("13900001091");verify(sms).verifyAndConsumeLoginCode("13900001091","123456");
        var capture=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mapper).updateInitialPassword(eq(9L),eq(7L),eq(u.getPassword()),capture.capture(),eq(u.getMobile()));
        assertTrue(encoder.matches("new12345",capture.getValue()));
    }
    @Test void wrongCodeAndAlreadySetCannotWritePassword() {
        var u=own();doThrow(new ServiceException(PHARMACY_MEMBER_SMS_CODE_INVALID)).when(sms).verifyAndConsumeLoginCode(anyString(),anyString());
        assertThrows(ServiceException.class,()->auth.setFirstPassword(9L,"new12345","bad"));
        verify(mapper,never()).updateInitialPassword(anyLong(),anyLong(),any(),anyString(),anyString());
        u.setPassword(encoder.encode("existing12"));reset(sms);
        assertThrows(ServiceException.class,()->auth.setFirstPassword(9L,"new12345","123456"));verifyNoInteractions(sms);
    }
    @Test void concurrentFirstSetCannotOverwriteWinner() {
        own();when(mapper.updateInitialPassword(anyLong(),anyLong(),any(),anyString(),anyString())).thenReturn(0);
        var ex=assertThrows(ServiceException.class,()->auth.setFirstPassword(9L,"new12345","123456"));
        assertEquals(PHARMACY_MEMBER_PASSWORD_ALREADY_SET.getCode(),ex.getCode());
    }
    @Test void disabledOrPhoneLessMemberCannotUseFirstSet() {
        var u=own();u.setStatus(1);assertThrows(ServiceException.class,()->auth.sendFirstPasswordCode(9L));
        u.setStatus(0);u.setMobile(null);assertThrows(ServiceException.class,()->auth.setFirstPassword(9L,"new12345","123456"));verifyNoInteractions(sms,tokens);
    }
}
