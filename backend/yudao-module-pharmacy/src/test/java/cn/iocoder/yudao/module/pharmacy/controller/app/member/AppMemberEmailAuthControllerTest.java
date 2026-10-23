package cn.iocoder.yudao.module.pharmacy.controller.app.member;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.controller.app.member.vo.auth.*;
import cn.iocoder.yudao.module.pharmacy.controller.app.member.vo.user.AppMemberUserUpdateReqVO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.MemberUserDO;
import cn.iocoder.yudao.module.pharmacy.service.member.MemberEmailAuthService;
import cn.iocoder.yudao.module.pharmacy.service.member.MemberUserService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppMemberEmailAuthControllerTest {
    final AppMemberEmailAuthController controller = new AppMemberEmailAuthController();
    final MemberEmailAuthService auth = mock(MemberEmailAuthService.class);

    @BeforeEach void setup() {
        TenantContextHolder.setTenantId(7L);
        ReflectionTestUtils.setField(controller, "memberEmailAuthService", auth);
    }
    @AfterEach void clean() { TenantContextHolder.clear(); SecurityContextHolder.clearContext(); }
    void login(int type, Long tenant) {
        var user = new LoginUser(); user.setId(9L); user.setUserType(type); user.setTenantId(tenant);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @Test void emailRegistrationNeedsNoPhoneAndPasswordIsOptional() throws Exception {
        var mapper = new ObjectMapper();
        var request = mapper.readValue("{\"email\":\"member@example.test\"}", AppMemberEmailRegisterReqVO.class);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(request).isEmpty());
        }
        controller.register(request, "123456");
        verify(auth).register(eq("member@example.test"), isNull(), eq("123456"), any());
        assertFalse(Arrays.stream(AppMemberEmailRegisterReqVO.class.getDeclaredFields()).anyMatch(f -> f.getName().equals("mobile")));
    }
    @Test void emailCodeIsReadFromHeaderAndPasswordsAreSanitized() throws Exception {
        for (var method : List.of(
                AppMemberEmailAuthController.class.getMethod("register", AppMemberEmailRegisterReqVO.class, String.class),
                AppMemberEmailAuthController.class.getMethod("login", AppMemberEmailLoginReqVO.class, String.class),
                AppMemberEmailAuthController.class.getMethod("setFirstPassword", AppMemberFirstPasswordReqVO.class, String.class))) {
            assertEquals("X-Email-Code", method.getParameters()[1].getAnnotation(RequestHeader.class).value());
        }
        for (var method : List.of(
                AppMemberEmailAuthController.class.getMethod("register", AppMemberEmailRegisterReqVO.class, String.class),
                AppMemberEmailAuthController.class.getMethod("passwordLogin", AppMemberEmailPasswordLoginReqVO.class),
                AppMemberEmailAuthController.class.getMethod("setFirstPassword", AppMemberFirstPasswordReqVO.class, String.class))) {
            assertTrue(Arrays.asList(method.getAnnotation(ApiAccessLog.class).sanitizeKeys()).contains("password"));
        }
        var password = new AppMemberEmailPasswordLoginReqVO(); password.setEmail("member@example.test"); password.setPassword("unit-secret");
        assertFalse(password.toString().contains("unit-secret"));
    }
    @Test void endpointMappingsKeepRegistrationAndLoginIndependent() {
        var names = Arrays.stream(AppMemberEmailAuthController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(PostMapping.class))
                .map(m -> m.getAnnotation(PostMapping.class).value()[0]).sorted().toList();
        assertEquals(List.of("/email-first-password", "/email-login", "/email-password-login", "/email-register",
                "/send-email-code", "/send-email-first-password-code"), names);
    }
    @Test void firstPasswordUsesAuthenticatedMemberOnlyAndCannotBePublic() throws Exception {
        login(UserTypeEnum.MEMBER.getValue(), 7L);
        var request = new AppMemberFirstPasswordReqVO(); request.setPassword("new12345");
        controller.sendFirstPasswordCode(); controller.setFirstPassword(request, "123456");
        verify(auth).sendFirstPasswordCode(eq(9L), any());
        verify(auth).setFirstPassword(eq(9L), eq("new12345"), eq("123456"), any());
        assertFalse(AppMemberEmailAuthController.class.getMethod("sendFirstPasswordCode").isAnnotationPresent(PermitAll.class));
        assertFalse(AppMemberEmailAuthController.class.getMethod("setFirstPassword", AppMemberFirstPasswordReqVO.class, String.class).isAnnotationPresent(PermitAll.class));
        assertEquals(0, AppMemberEmailAuthController.class.getMethod("sendFirstPasswordCode").getParameterCount());
    }
    @Test void unauthenticatedAdminAndWrongTenantCannotRequestOrSetFirstPassword() {
        var request = new AppMemberFirstPasswordReqVO(); request.setPassword("new12345");
        assertThrows(AccessDeniedException.class, controller::sendFirstPasswordCode);
        for (int type : new int[]{UserTypeEnum.ADMIN.getValue(), UserTypeEnum.MEMBER.getValue()}) {
            login(type, 8L);
            assertThrows(AccessDeniedException.class, controller::sendFirstPasswordCode);
            assertThrows(AccessDeniedException.class, () -> controller.setFirstPassword(request, "123456"));
        }
        verifyNoInteractions(auth);
    }
    @Test void profileExposesVerifiedIdentitySeparatelyAndProfileUpdateCannotChooseIt() throws Exception {
        login(UserTypeEnum.MEMBER.getValue(), 7L);
        var profile = new AppMemberUserController(); var users = mock(MemberUserService.class);
        ReflectionTestUtils.setField(profile, "memberUserService", users);
        ReflectionTestUtils.setField(profile, "memberEmailAuthService", auth);
        var user = new MemberUserDO(); user.setId(9L); user.setEmail("contact@example.test");
        when(users.validateMemberUserExists(9L)).thenReturn(user);
        when(auth.getLoginEmail(9L)).thenReturn("verified@example.test");
        var result = profile.getUserInfo().getData();
        assertEquals("contact@example.test", result.getEmail()); assertEquals("verified@example.test", result.getLoginEmail()); assertNull(result.getMobile());
        var mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        var update = mapper.readValue("{\"email\":\"new-contact@example.test\",\"loginEmail\":\"attacker@example.test\",\"userId\":666}", AppMemberUserUpdateReqVO.class);
        profile.updateUserInfo(update);
        verify(users).updateMemberUserProfile(9L, null, null, null, "new-contact@example.test");
        verify(auth, times(1)).getLoginEmail(9L);
        verifyNoMoreInteractions(auth);
    }
    @Test void publicSendCannotChooseFirstPasswordSceneAndPasswordLoginAcceptsLegacyLength() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var send = new AppMemberEmailCodeReqVO(); send.setEmail("member@example.test"); send.setScene("first-password");
            assertFalse(validator.validate(send).isEmpty());
            var legacy = new AppMemberEmailPasswordLoginReqVO(); legacy.setEmail("member@example.test"); legacy.setPassword("short");
            assertTrue(validator.validate(legacy).isEmpty());
        }
    }
}
