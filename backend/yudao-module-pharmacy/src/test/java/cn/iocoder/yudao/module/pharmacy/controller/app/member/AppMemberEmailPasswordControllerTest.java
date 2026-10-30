package cn.iocoder.yudao.module.pharmacy.controller.app.member;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.controller.app.member.vo.auth.*;
import cn.iocoder.yudao.module.pharmacy.service.member.MemberEmailAuthService;
import jakarta.annotation.security.PermitAll;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppMemberEmailPasswordControllerTest {
    final MemberEmailAuthService service=mock(MemberEmailAuthService.class);
    final AppMemberEmailPasswordController controller=new AppMemberEmailPasswordController();
    @BeforeEach void setup(){TenantContextHolder.setTenantId(7L);ReflectionTestUtils.setField(controller,"memberEmailAuthService",service);}
    @AfterEach void clean(){TenantContextHolder.clear();SecurityContextHolder.clearContext();}
    void login(int type,long tenant){var user=new LoginUser();user.setId(9L);user.setTenantId(tenant);user.setUserType(type);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));}
    @Test void resetUsesOnlyHeaderProofAndSensitiveBodiesAreSanitized()throws Exception{
        var method=AppMemberEmailPasswordController.class.getMethod("resetPassword",AppMemberEmailResetPasswordReqVO.class,String.class);
        var header=Arrays.stream(method.getParameterAnnotations()[1]).filter(RequestHeader.class::isInstance).map(RequestHeader.class::cast).findFirst().orElseThrow();
        assertEquals("X-Email-Code",header.value());assertFalse(header.required());assertNotNull(method.getAnnotation(PermitAll.class));assertTrue(Arrays.asList(method.getAnnotation(ApiAccessLog.class).sanitizeKeys()).contains("password"));
        var change=AppMemberEmailPasswordController.class.getMethod("changePassword",AppMemberEmailChangePasswordReqVO.class);
        assertNull(change.getAnnotation(PermitAll.class));assertTrue(Arrays.asList(change.getAnnotation(ApiAccessLog.class).sanitizeKeys()).containsAll(List.of("oldPassword","password")));
        assertEquals(2,AppMemberEmailChangePasswordReqVO.class.getDeclaredFields().length);assertEquals(2,AppMemberEmailResetPasswordReqVO.class.getDeclaredFields().length);
    }
    @Test void requestSendAndResetReturnOnlyUniformSuccess(){
        var send=new AppMemberEmailLoginReqVO();send.setEmail("member@example.test");assertEquals(true,controller.sendResetCode(send).getData());
        var reset=new AppMemberEmailResetPasswordReqVO();reset.setEmail("member@example.test");reset.setPassword("new12345");assertEquals(true,controller.resetPassword(reset,"123456").getData());
        verify(service).sendResetCode(eq(send.getEmail()),any());verify(service).resetPassword(eq(reset.getEmail()),eq("new12345"),eq("123456"),any());
        assertFalse(reset.toString().contains("new12345"));
    }
    @Test void changeOnlySelectsCurrentPrincipalAndRejectsStaffWrongTenantAndGuests(){
        var request=new AppMemberEmailChangePasswordReqVO();request.setOldPassword("old");request.setPassword("new12345");
        login(UserTypeEnum.MEMBER.getValue(),7L);controller.changePassword(request);verify(service).changePassword(eq(9L),eq("old"),eq("new12345"),any());reset(service);
        login(UserTypeEnum.ADMIN.getValue(),7L);assertThrows(AccessDeniedException.class,()->controller.changePassword(request));
        login(UserTypeEnum.MEMBER.getValue(),8L);assertThrows(AccessDeniedException.class,()->controller.changePassword(request));
        SecurityContextHolder.clearContext();assertThrows(AccessDeniedException.class,()->controller.changePassword(request));verifyNoInteractions(service);
        assertFalse(request.toString().contains("new12345"));assertFalse(request.toString().contains("old"));
    }
}
