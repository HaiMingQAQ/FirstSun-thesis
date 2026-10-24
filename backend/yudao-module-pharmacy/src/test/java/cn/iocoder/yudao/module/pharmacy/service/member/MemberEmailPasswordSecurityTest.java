package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.*;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.member.*;
import org.junit.jupiter.api.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import static cn.iocoder.yudao.module.pharmacy.enums.member.MemberEmailErrorCodes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemberEmailPasswordSecurityTest {
    final MemberEmailAuthServiceImpl service=new MemberEmailAuthServiceImpl();
    final MemberEmailIdentityMapper identities=mock(MemberEmailIdentityMapper.class);
    final MemberUserMapper mapper=mock(MemberUserMapper.class);
    final MemberUserServiceImpl users=new MemberUserServiceImpl();
    final MemberEmailCodeService codes=mock(MemberEmailCodeService.class);
    final OAuth2TokenCommonApi tokens=mock(OAuth2TokenCommonApi.class);
    final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder();
    MemberUserDO member;MemberEmailIdentityDO identity;
    @BeforeEach void setup(){
        TenantContextHolder.setTenantId(7L);ReflectionTestUtils.setField(users,"memberUserMapper",mapper);
        ReflectionTestUtils.setField(service,"memberUserService",users);ReflectionTestUtils.setField(service,"memberEmailIdentityMapper",identities);
        ReflectionTestUtils.setField(service,"memberEmailCodeService",codes);ReflectionTestUtils.setField(service,"oauth2TokenApi",tokens);
        member=new MemberUserDO();member.setId(9L);member.setTenantId(7L);member.setStatus(0);member.setPassword(encoder.encode("old12345"));
        identity=new MemberEmailIdentityDO();identity.setId(19L);identity.setTenantId(7L);identity.setMemberId(9L);identity.setNormalizedEmail("member@example.test");
        when(identities.selectByEmail(7L,"member@example.test")).thenReturn(identity);when(identities.selectByMember(7L,9L)).thenReturn(identity);
        when(mapper.selectByIdAndTenantId(9L,7L)).thenReturn(member);
        when(mapper.updateInitialEmailPassword(eq(9L),eq(7L),any(),anyString(),eq(19L),eq("member@example.test"))).thenReturn(1);
    }
    @AfterEach void clean(){TenantContextHolder.clear();}
    @Test void changeUsesOldPasswordAndCapturedIdentityWithoutMailOrCode(){
        service.changePassword(9L,"old12345"," new12345 ","ip");
        var hash=org.mockito.ArgumentCaptor.forClass(String.class);verify(mapper).updateInitialEmailPassword(eq(9L),eq(7L),eq(member.getPassword()),hash.capture(),eq(19L),eq("member@example.test"));
        assertTrue(encoder.matches(" new12345 ",hash.getValue()));verify(tokens).removeAccessToken(9L,UserTypeEnum.MEMBER.getValue());
        verify(codes,never()).sendCode(any(),any(),any());verify(codes,never()).verifyAndConsume(any(),any(),any(),any());
    }
    @Test void wrongOldPasswordAndUnsetPasswordNeverWriteOrRevoke(){
        assertEquals(EMAIL_OLD_PASSWORD_INVALID.getCode(),assertThrows(ServiceException.class,()->service.changePassword(9L,"wrong","new12345","ip")).getCode());
        member.setPassword(encoder.encode(""));
        assertEquals(EMAIL_PASSWORD_NOT_SET.getCode(),assertThrows(ServiceException.class,()->service.changePassword(9L,"","new12345","ip")).getCode());
        verify(mapper,never()).updateInitialEmailPassword(anyLong(),anyLong(),any(),any(),anyLong(),any());verifyNoInteractions(tokens);
    }
    @Test void inactiveDeletedUnverifiedAndCrossTenantAddressesGetOnlyAnIneligibleSend(){
        when(identities.selectByEmail(7L,"contact@example.test")).thenReturn(null);service.sendResetCode("contact@example.test","ip");
        member.setStatus(1);service.sendResetCode("member@example.test","ip");
        member.setStatus(0);identity.setDeleted(true);service.sendResetCode("member@example.test","ip");
        identity.setDeleted(false);identity.setTenantId(8L);service.sendResetCode("member@example.test","ip");
        verify(codes).sendPasswordResetCode("contact@example.test",null,"ip");verify(codes,times(3)).sendPasswordResetCode("member@example.test",null,"ip");
        assertThrows(ServiceException.class,()->service.resetPassword("member@example.test","new12345","123456","ip"));verifyNoInteractions(tokens);
    }
    @Test void resetBindingChangesWithIdentityPasswordOrMemberRevision(){
        var capture=org.mockito.ArgumentCaptor.forClass(String.class);
        service.sendResetCode("member@example.test","ip");member.setPassword(encoder.encode("new12345"));service.sendResetCode("member@example.test","ip");
        identity.setId(20L);service.sendResetCode("member@example.test","ip");member.setUpdateTime(LocalDateTime.now());service.sendResetCode("member@example.test","ip");
        verify(codes,times(4)).sendPasswordResetCode(eq("member@example.test"),capture.capture(),eq("ip"));
        assertEquals(4,new java.util.HashSet<>(capture.getAllValues()).size());assertTrue(capture.getAllValues().stream().allMatch(x->x.length()==64));
    }
    @Test void wrongResetCodeOrLostCasNeverRevokesAnySession(){
        doThrow(new ServiceException(EMAIL_CODE_INVALID)).when(codes).verifyPasswordResetCode(any(),any(),any(),any());
        assertThrows(ServiceException.class,()->service.resetPassword("member@example.test","new12345","wrong","ip"));verifyNoInteractions(tokens);
        reset(codes);when(mapper.updateInitialEmailPassword(anyLong(),anyLong(),any(),anyString(),anyLong(),anyString())).thenReturn(0);
        assertEquals(EMAIL_PASSWORD_STATE_CHANGED.getCode(),assertThrows(ServiceException.class,()->service.resetPassword("member@example.test","new12345","123456","ip")).getCode());
        verifyNoInteractions(tokens);
    }
    @Test void resetSuccessVerifiesOnlyBoundResetProofAndRevokesTargetMember(){
        service.resetPassword("member@example.test","new12345","123456","ip");
        var ordered=inOrder(codes,mapper,tokens);ordered.verify(codes).ensureAvailable();
        ordered.verify(mapper).selectByIdAndTenantId(9L,7L);
        ordered.verify(codes).verifyPasswordResetCode(eq("member@example.test"),anyString(),eq("123456"),eq("ip"));
        ordered.verify(mapper).updateInitialEmailPassword(eq(9L),eq(7L),eq(member.getPassword()),anyString(),eq(19L),eq("member@example.test"));
        ordered.verify(tokens).removeAccessToken(9L,UserTypeEnum.MEMBER.getValue());
        verify(codes,never()).verifyAndConsume(any(),any(),any(),any());
    }
}
