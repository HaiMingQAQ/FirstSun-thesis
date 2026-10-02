package cn.iocoder.yudao.module.ai.service.pharmacy;
import cn.hutool.crypto.SecureUtil;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.ai.controller.app.pharmacy.vo.CustomerAiConsultReqVO;
import cn.iocoder.yudao.module.ai.dal.dataobject.pharmacy.CustomerAiConsultDO;
import cn.iocoder.yudao.module.ai.dal.mysql.pharmacy.CustomerAiConsultMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class CustomerAiConsultServiceTest {
    final CustomerAiConsultMapper mapper = mock(CustomerAiConsultMapper.class);
    final CustomerAiIntentService intent = mock(CustomerAiIntentService.class);
    final CustomerAiCatalogueService catalogue = mock(CustomerAiCatalogueService.class);
    final CustomerAiConsultService service = new CustomerAiConsultService(mapper, intent, catalogue, new ObjectMapper().findAndRegisterModules(), mock(CustomerAiAnswerService.class), mock(CustomerAiHistoryService.class));
    final CustomerAiConsultReqVO req = new CustomerAiConsultReqVO();
    @BeforeEach void setup() {
        req.setClientMessageId("request_1"); req.setContent("查询板蓝根"); req.setStoreId(1L);
        login(UserTypeEnum.MEMBER.getValue(), 7L); TenantContextHolder.setTenantId(7L);
        when(mapper.insert(any(CustomerAiConsultDO.class))).thenAnswer(i -> { ((CustomerAiConsultDO)i.getArgument(0)).setId(9L); return 1; });
        when(mapper.finish(anyLong(), anyString(), anyString())).thenReturn(1);
    }
    void login(Integer type, Long tenant) {
        var user = new LoginUser(); user.setId(2L); user.setTenantId(tenant); user.setUserType(type);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); TenantContextHolder.clear(); }
    @Test void rejectsAnonymousAdminAndTenantMismatchBeforeReading() {
        SecurityContextHolder.clearContext(); assertThrows(AccessDeniedException.class, () -> service.consult(req));
        login(UserTypeEnum.ADMIN.getValue(),7L); assertThrows(AccessDeniedException.class, () -> service.consult(req));
        login(UserTypeEnum.MEMBER.getValue(),8L); assertThrows(AccessDeniedException.class, () -> service.consult(req));
        verifyNoInteractions(mapper, catalogue, intent);
    }
    CustomerAiConsultDO pending() {
        var row = new CustomerAiConsultDO(); row.setId(9L); row.setStatus("PENDING");
        row.setRequestHash(SecureUtil.sha256("1\n查询板蓝根")); row.setExpiresAt(LocalDateTime.now().plusSeconds(60)); return row;
    }
    @Test void duplicatePendingDoesNotCallModel() {
        when(mapper.find(2L,"request_1")).thenReturn(pending());
        assertEquals("PENDING",service.consult(req).status()); verifyNoInteractions(intent);
        verify(mapper,never()).insert(any(CustomerAiConsultDO.class));
    }
    @Test void changedPayloadCannotReuseRequestId() {
        var row = pending(); row.setRequestHash("other"); when(mapper.find(2L,"request_1")).thenReturn(row);
        assertEquals(400, assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,() -> service.consult(req)).getCode()); verifyNoInteractions(intent);
    }
    @Test void changedContextCannotReuseRequestId() {
        when(mapper.find(2L,"request_1")).thenReturn(pending());
        req.setContext(List.of(new CustomerAiConsultReqVO.Turn("user","我正在服用其他药")));
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,()->service.consult(req));
        verifyNoInteractions(intent);
    }
    @Test void streamingAlsoRejectsAdminBeforeSchedulingWork() {
        login(UserTypeEnum.ADMIN.getValue(),7L);
        assertThrows(AccessDeniedException.class,()->service.stream(req));
        verifyNoInteractions(mapper,catalogue,intent);
    }
    @Test void expiredPendingBecomesFailure() {
        var row=pending(); row.setExpiresAt(LocalDateTime.now().minusSeconds(1)); when(mapper.find(2L,"request_1")).thenReturn(row);
        assertEquals("FAILED",service.consult(req).status()); verify(mapper).finish(eq(9L),eq("FAILED"),anyString()); verifyNoInteractions(intent);
    }
    @Test void modelFailureDoesNotDiscloseProviderDetails() {
        when(intent.resolve(anyString())).thenThrow(new IllegalStateException("credential=PRIVATE_SECRET"));
        var response=service.consult(req); assertEquals("FAILED",response.status());
        assertFalse(response.answer().contains("PRIVATE_SECRET"));
        verify(mapper).finish(eq(9L),eq("FAILED"),argThat(s -> !s.contains("PRIVATE_SECRET")));
    }
    @Test void expiredReplayReturnsConcurrentSuccessInsteadOfInventingFailure() throws Exception {
        var expired=pending(); expired.setMemberId(2L); expired.setClientMessageId("request_1");
        expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        var winner=pending(); winner.setStatus("SUCCESS");
        var response=new cn.iocoder.yudao.module.ai.controller.app.pharmacy.vo.CustomerAiConsultRespVO(
                "request_1","SUCCESS","已完成","数据库",LocalDateTime.now(),false,List.of());
        winner.setResultJson(new ObjectMapper().findAndRegisterModules().writeValueAsString(response));
        when(mapper.find(2L,"request_1")).thenReturn(expired,winner);
        when(mapper.finish(eq(9L),eq("FAILED"),anyString())).thenReturn(0);
        assertEquals("SUCCESS",service.consult(req).status()); verifyNoInteractions(intent);
    }
    @Test void refusalNeverRunsCatalogueQuery() {
        when(intent.resolve(anyString())).thenReturn(new CustomerAiIntentService.Intent("refuse",null,null));
        assertTrue(service.consult(req).products().isEmpty()); verify(catalogue,never()).query(anyLong(),any(),any());
    }
    @Test void lostFinalizationCannotReportSuccess() {
        when(intent.resolve(anyString())).thenReturn(new CustomerAiIntentService.Intent("refuse",null,null));
        when(mapper.finish(anyLong(),anyString(),anyString())).thenReturn(0);
        assertEquals("FAILED",service.consult(req).status());
    }
}
