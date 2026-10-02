package cn.iocoder.yudao.module.pharmacy.service.prescription;

import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.controller.app.prescription.vo.AppPrescRecordCreateReqVO;
import cn.iocoder.yudao.module.pharmacy.controller.admin.prescription.vo.*;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.base.DrugDO;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.prescription.PhPrescRecordDO;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.prescription.PrescRecordMapper;
import cn.iocoder.yudao.module.pharmacy.service.base.DrugService;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AppPrescriptionServiceTest {
    final PrivateMaterialService materials=mock(PrivateMaterialService.class);
    final PrescRecordService records=mock(PrescRecordService.class);
    final PrescRecordMapper mapper=mock(PrescRecordMapper.class);
    final DrugService drugs=mock(DrugService.class);
    final AppPrescriptionService service=new AppPrescriptionService(materials,records,mapper,drugs);
    AppPrescRecordCreateReqVO req;
    @BeforeEach void setup() {
        var login=new LoginUser();login.setId(2L);login.setTenantId(7L);login.setUserType(UserTypeEnum.MEMBER.getValue());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login,null,List.of()));TenantContextHolder.setTenantId(7L);
        req=new AppPrescRecordCreateReqVO();req.setClientRequestId("request-1");req.setStoreId(3L);req.setPatientName("测试");req.setMaterialIds(List.of(4L));
        var item=new PrescItemVO();item.setDrugId(5L);item.setQty(2);item.setDrugName("伪造名称");req.setItems(List.of(item));
        var drug=new DrugDO();drug.setId(5L);drug.setGenericName("数据库药名");drug.setStatus(1);drug.setApproveStatus(1);drug.setSaleableOnline(1);when(drugs.getDrug(5L)).thenReturn(drug);
    }
    @AfterEach void clear(){TenantContextHolder.clear();SecurityContextHolder.clearContext();}
    @Test void generatedNumberFitsExistingColumnAndUsesCanonicalDrug() {
        when(records.createPrescRecord(any())).thenAnswer(call->{PrescRecordSaveReqVO save=call.getArgument(0);
            assertEquals(32,save.getPrescNo().length());assertEquals("数据库药名",save.getItems().get(0).getDrugName());assertEquals(List.of("private:4"),save.getImages());return 9L;});
        assertEquals(9L,service.submit(req));verify(materials).requireOwned(List.of(4L),2L,3L);
    }
    @Test void duplicateDetectedInsideRegisterReturnsOnlyMatchingSubmission() {
        var existing=new PhPrescRecordDO();existing.setId(9L);
        when(mapper.selectByPrescNo(anyString())).thenReturn(null,existing);
        when(records.createPrescRecord(any())).thenAnswer(call->{PrescRecordSaveReqVO save=call.getArgument(0);existing.setSubmissionHash(save.getSubmissionHash());
            throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(cn.iocoder.yudao.module.pharmacy.enums.ErrorCodeConstants.PRESC_NO_DUPLICATE);});
        assertEquals(9L,service.submit(req));
        existing.setSubmissionHash("other");when(mapper.selectByPrescNo(anyString())).thenReturn(existing);
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,()->service.submit(req));
    }
    @Test void inputConstraintsMatchExistingColumnsAndRejectNullDetails() {
        req.setPatientName("名".repeat(33));req.setDoctorName("医".repeat(33));req.setStoreId(0L);
        req.setMaterialIds(java.util.Arrays.asList((Long)null));req.setItems(java.util.Arrays.asList((PrescItemVO)null));
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var paths=factory.getValidator().validate(req).stream().map(v->v.getPropertyPath().toString()).toList();
            assertTrue(paths.contains("patientName"));assertTrue(paths.contains("doctorName"));assertTrue(paths.contains("storeId"));
            assertTrue(paths.stream().anyMatch(v->v.startsWith("items")));assertTrue(paths.stream().anyMatch(v->v.startsWith("materialIds")));
        }
    }
}
