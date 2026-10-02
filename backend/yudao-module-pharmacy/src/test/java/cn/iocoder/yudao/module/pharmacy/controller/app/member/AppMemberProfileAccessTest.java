package cn.iocoder.yudao.module.pharmacy.controller.app.member;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.controller.app.member.vo.user.AppMemberUserUpdateReqVO;
import cn.iocoder.yudao.module.pharmacy.service.member.MemberUserService;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AppMemberProfileAccessTest {
    final AppMemberUserController controller = new AppMemberUserController();
    final MemberUserService users = mock(MemberUserService.class);
    @BeforeEach void setup() { ReflectionTestUtils.setField(controller, "memberUserService", users); TenantContextHolder.setTenantId(7L); }
    @AfterEach void clean() { SecurityContextHolder.clearContext(); TenantContextHolder.clear(); }
    void login(int type) { var user = new LoginUser(); user.setId(9L); user.setTenantId(7L); user.setUserType(type); SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, java.util.List.of())); }
    @Test void rejectsStaffProfileReadAndUpdate() {
        login(UserTypeEnum.ADMIN.getValue());
        assertThrows(AccessDeniedException.class, controller::getUserInfo);
        assertThrows(AccessDeniedException.class, () -> controller.updateUserInfo(new AppMemberUserUpdateReqVO())); verifyNoInteractions(users);
    }
    @Test void updatesOnlyTheAuthenticatedMember() {
        login(UserTypeEnum.MEMBER.getValue()); var request = new AppMemberUserUpdateReqVO(); request.setNickname("测试昵称");
        controller.updateUserInfo(request); verify(users).updateMemberUserProfile(9L, "测试昵称", null, null);
    }
}
