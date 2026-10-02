package cn.iocoder.yudao.module.ai.service.pharmacy;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.ai.dal.dataobject.pharmacy.*;
import cn.iocoder.yudao.module.ai.dal.mysql.pharmacy.*;
import cn.iocoder.yudao.module.ai.controller.app.pharmacy.vo.CustomerAiConsultReqVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class CustomerAiHistoryServiceTest {
    final CustomerAiTopicMapper topics = mock(CustomerAiTopicMapper.class);
    final CustomerAiConsultMapper consults = mock(CustomerAiConsultMapper.class);
    final CustomerAiHistoryService history = new CustomerAiHistoryService(topics, consults, mock(CustomerAiCatalogueService.class), new ObjectMapper());
    @BeforeEach void login() {
        TenantContextHolder.setTenantId(7L);
        var user = new LoginUser(); user.setId(9L); user.setTenantId(7L); user.setUserType(UserTypeEnum.MEMBER.getValue());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, java.util.List.of()));
    }
    @AfterEach void clean() { SecurityContextHolder.clearContext(); TenantContextHolder.clear(); }
    @Test void rejectsOtherMemberAndTenantEvenWithAMapperResult() {
        var topic = new CustomerAiTopicDO(); topic.setId(1L); topic.setMemberId(10L); topic.setTenantId(7L);
        when(topics.selectById(1L)).thenReturn(topic);
        assertThrows(RuntimeException.class, () -> history.detail(1L, null));
        topic.setMemberId(9L); topic.setTenantId(8L);
        assertThrows(RuntimeException.class, () -> history.detail(1L, null)); verifyNoInteractions(consults);
    }
    @Test void staffCannotListPrivateHistory() {
        var user = new LoginUser(); user.setId(9L); user.setUserType(UserTypeEnum.ADMIN.getValue());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, java.util.List.of()));
        assertThrows(AccessDeniedException.class, () -> history.list(null)); verifyNoInteractions(topics, consults);
    }
    @Test void deletedOrWrongStoreTopicCannotAttachAQuestion() {
        var request = new CustomerAiConsultReqVO(); request.setTopicId(1L); request.setStoreId(407L); request.setContent("测试问题");
        var row = new CustomerAiConsultDO(); row.setMemberId(9L);
        assertThrows(RuntimeException.class, () -> history.attach(row, request));
        var topic = new CustomerAiTopicDO(); topic.setId(1L); topic.setStoreId(408L);
        when(topics.lock(1L, 7L, 9L)).thenReturn(topic);
        assertThrows(RuntimeException.class, () -> history.attach(row, request)); verifyNoInteractions(consults);
    }
    @Test void unavailableTopicCannotBeDeletedOrEraseAnotherMembersRecords() {
        assertThrows(RuntimeException.class, () -> history.delete(1L));
        verify(topics).lock(1L, 7L, 9L); verify(topics, never()).erase(anyLong(), anyLong(), anyLong()); verifyNoInteractions(consults);
    }
}
