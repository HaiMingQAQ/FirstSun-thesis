package cn.iocoder.yudao.module.pharmacy.service.consultation;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.controller.app.consultation.ConsultationRequests;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.consultation.*;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.base.*;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.consultation.*;
import cn.iocoder.yudao.module.pharmacy.service.base.*;
import cn.iocoder.yudao.module.pharmacy.service.permission.PharmacyStoreDataAccess;
import cn.iocoder.yudao.module.pharmacy.service.prescription.PrescriptionStaffAccess;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ConsultationServiceTest {
    final ConsultationMapper conversations=mock(ConsultationMapper.class);
    final ConsultationMessageMapper messages=mock(ConsultationMessageMapper.class);
    final StoreService stores=mock(StoreService.class);
    final EmployeeService employees=mock(EmployeeService.class);
    final PharmacyStoreDataAccess access=mock(PharmacyStoreDataAccess.class);
    final PrescriptionStaffAccess pharmacists=mock(PrescriptionStaffAccess.class);
    final ConsultationService service=new ConsultationService(conversations,messages,stores,employees,access,pharmacists);
    ConsultationDO row;
    void login(boolean staff){var user=new LoginUser();user.setId(staff?8L:2L);user.setTenantId(7L);user.setUserType((staff?UserTypeEnum.ADMIN:UserTypeEnum.MEMBER).getValue());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));TenantContextHolder.setTenantId(7L);}
    @BeforeEach void setup(){login(false);row=new ConsultationDO();row.setId(1L);row.setMemberId(2L);row.setStoreId(3L);row.setKind("SERVICE");row.setLastMessageId(10L);row.setMemberReadId(5L);row.setStaffReadId(0L);when(conversations.lock(1L)).thenReturn(row);when(conversations.selectById(1L)).thenReturn(row);var store=new StoreDO();store.setStatus(1);when(stores.getStore(3L)).thenReturn(store);}
    @AfterEach void clear(){TenantContextHolder.clear();SecurityContextHolder.clearContext();}
    @Test void unauthorizedPrincipalOrOwnerCannotReadOrSend(){row.setMemberId(9L);assertThrows(AccessDeniedException.class,()->service.thread(1L,false,0L,null));assertThrows(AccessDeniedException.class,()->service.send(new ConsultationRequests.Send(1L,"request_1","hello"),false));verifyNoInteractions(messages);row.setMemberId(2L);TenantContextHolder.setTenantId(9L);assertThrows(AccessDeniedException.class,()->service.thread(1L,false,0L,null));}
    @Test void replayChecksContentWithoutInsertingAnotherMessage(){var previous=new ConsultationMessageDO();previous.setId(10L);previous.setContentHash(cn.hutool.crypto.digest.DigestUtil.sha256Hex("hello"));when(messages.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(previous);assertEquals(10L,service.send(new ConsultationRequests.Send(1L,"request_1"," hello "),false));assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,()->service.send(new ConsultationRequests.Send(1L,"request_1","changed"),false));verify(messages,never()).insert(any(ConsultationMessageDO.class));}
    @Test void readCursorNeverRegressesAndRejectsAnotherConversation(){var message=new ConsultationMessageDO();message.setId(4L);message.setConversationId(1L);when(messages.selectById(4L)).thenReturn(message);service.read(new ConsultationRequests.Read(1L,4L),false);assertEquals(5L,row.getMemberReadId());message.setConversationId(9L);assertThrows(AccessDeniedException.class,()->service.read(new ConsultationRequests.Read(1L,4L),false));}
    @Test void messageLookupHoldsConversationLockThroughAuthorization() {
        when(messages.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of());
        service.thread(1L,false,0L,null);
        var ordered=inOrder(conversations,messages);ordered.verify(conversations).lock(1L);ordered.verify(messages).selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(conversations,never()).selectById(1L);
    }
    @Test void staffMustBelongToStoreClaimAndPassPharmacistCheck(){login(true);var employee=new EmployeeDO();employee.setUserId(8L);employee.setStoreId(3L);employee.setTenantId(7L);employee.setStatus(1);employee.setEmpName("测试接待");when(access.scopeStoreId(3L)).thenReturn(3L);when(employees.getEmployeeByUserId(8L)).thenReturn(employee);assertThrows(AccessDeniedException.class,()->service.send(new ConsultationRequests.Send(1L,"request_1","reply"),true));service.claim(1L);assertEquals(8L,row.getStaffId());row.setStaffId(9L);assertThrows(AccessDeniedException.class,()->service.claim(1L));row.setStaffId(null);row.setKind("PHARMACIST");doThrow(new AccessDeniedException("not qualified")).when(pharmacists).requirePharmacist(3L);assertThrows(AccessDeniedException.class,()->service.claim(1L));employee.setStatus(0);assertThrows(AccessDeniedException.class,()->service.thread(1L,true,0L,null));}
}
