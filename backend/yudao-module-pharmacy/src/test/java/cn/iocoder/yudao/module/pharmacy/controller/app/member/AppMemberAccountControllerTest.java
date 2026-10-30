package cn.iocoder.yudao.module.pharmacy.controller.app.member;

import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.controller.app.member.vo.auth.*;
import cn.iocoder.yudao.module.pharmacy.controller.app.member.vo.user.*;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.MemberUserDO;
import cn.iocoder.yudao.module.pharmacy.service.member.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppMemberAccountControllerTest {
    final AppMemberUserController controller=new AppMemberUserController();
    final MemberUserService users=mock(MemberUserService.class);
    final MemberAuthService auth=mock(MemberAuthService.class);
    final MemberEmailAuthService emailAuth=mock(MemberEmailAuthService.class);
    final MemberAvatarService avatars=mock(MemberAvatarService.class);
    @BeforeEach void setup() {
        TenantContextHolder.setTenantId(7L);ReflectionTestUtils.setField(controller,"memberUserService",users);
        ReflectionTestUtils.setField(controller,"memberAuthService",auth);ReflectionTestUtils.setField(controller,"memberAvatarService",avatars);
        ReflectionTestUtils.setField(controller,"memberEmailAuthService",emailAuth);
    }
    @AfterEach void clean(){TenantContextHolder.clear();SecurityContextHolder.clearContext();}
    void login(int type,Long tenant) {
        var u=new LoginUser();u.setId(9L);u.setTenantId(tenant);u.setUserType(type);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u,null,java.util.List.of()));
    }
    @Test void emailOmissionAndExplicitClearingAreDifferentAndClientCannotChooseMember()throws Exception {
        login(UserTypeEnum.MEMBER.getValue(),7L);var json=new ObjectMapper().configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
        var omit=json.readValue("{\"nickname\":\"N\",\"userId\":666,\"tenantId\":8}",AppMemberUserUpdateReqVO.class);controller.updateUserInfo(omit);
        verify(users).updateMemberUserProfile(9L,"N",null,null);
        var clear=json.readValue("{\"email\":\"\"}",AppMemberUserUpdateReqVO.class);controller.updateUserInfo(clear);
        verify(users).updateMemberUserProfile(9L,null,null,null,"");
    }
    @Test void firstSetOnlyUsesPrincipalAndHasNoIdentitySelector()throws Exception {
        login(UserTypeEnum.MEMBER.getValue(),7L);var req=new AppMemberFirstPasswordReqVO();req.setPassword("abc12345");
        controller.sendPasswordCode();controller.setPassword(req,"123456");
        verify(auth).sendFirstPasswordCode(9L);verify(auth).setFirstPassword(9L,"abc12345","123456");
        assertEquals(1,AppMemberFirstPasswordReqVO.class.getDeclaredFields().length);
        var method=AppMemberUserController.class.getMethod("setPassword",AppMemberFirstPasswordReqVO.class,String.class);
        assertTrue(java.util.Arrays.asList(method.getAnnotation(cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog.class).sanitizeKeys()).contains("password"));
    }
    @Test void adminWrongTenantAndUnauthenticatedCannotUseNewActions()throws Exception {
        for(int type:new int[]{UserTypeEnum.ADMIN.getValue(),UserTypeEnum.MEMBER.getValue()}) {
            login(type,8L);assertThrows(AccessDeniedException.class,controller::sendPasswordCode);
            assertThrows(AccessDeniedException.class,()->controller.uploadAvatar(null));
        }
        SecurityContextHolder.clearContext();assertThrows(AccessDeniedException.class,controller::sendPasswordCode);
        verifyNoInteractions(auth,avatars,users);
    }
    @Test void profileExposesOnlyBooleanPasswordState() {
        login(UserTypeEnum.MEMBER.getValue(),7L);var user=new MemberUserDO();user.setId(9L);user.setEmail("a@example.test");user.setPassword("unit-hash");
        when(users.validateMemberUserExists(9L)).thenReturn(user);when(users.isPasswordSet("unit-hash")).thenReturn(true);
        var result=controller.getUserInfo().getData();assertEquals("a@example.test",result.getEmail());assertTrue(result.getPasswordSet());
        assertFalse(result.toString().contains("unit-hash"));
    }
    @Test void emailAndLegacyShortPasswordValidation() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var v=factory.getValidator();var req=new AppMemberRegisterReqVO();req.setMobile("13900001091");req.setEmail(" a@example.test ");assertTrue(v.validate(req).isEmpty());
            req.setEmail("bad");assertFalse(v.validate(req).isEmpty());req.setEmail("a".repeat(256));assertFalse(v.validate(req).isEmpty());
            var legacy=new AppMemberAuthLoginReqVO();legacy.setMobile("13900001091");legacy.setPassword("short");assertTrue(v.validate(legacy).isEmpty());
        }
    }
}
