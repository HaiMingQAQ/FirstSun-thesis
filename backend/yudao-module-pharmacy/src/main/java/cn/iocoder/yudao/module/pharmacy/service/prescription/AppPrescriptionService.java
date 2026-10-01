package cn.iocoder.yudao.module.pharmacy.service.prescription;
import cn.hutool.crypto.SecureUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.controller.app.prescription.vo.AppPrescRecordCreateReqVO;
import cn.iocoder.yudao.module.pharmacy.controller.admin.prescription.vo.*;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.prescription.PrescRecordMapper;
import cn.iocoder.yudao.module.pharmacy.service.member.AppMemberAccess;
import cn.iocoder.yudao.module.pharmacy.service.base.DrugService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.invalidParamException;
@Service @RequiredArgsConstructor
public class AppPrescriptionService {
    private final PrivateMaterialService materials;
    private final PrescRecordService records;
    private final PrescRecordMapper mapper;
    private final DrugService drugs;
    public Long submit(AppPrescRecordCreateReqVO req) {
        Long member=AppMemberAccess.requireMember();materials.requireOwned(req.getMaterialIds(),member,req.getStoreId());
        var items=new ArrayList<PrescItemVO>();var seen=new HashSet<Long>();
        for(var requested:req.getItems()) {
            if(!seen.add(requested.getDrugId())||requested.getQty()==null||requested.getQty()<1||requested.getQty()>999)
                throw invalidParamException("药品不得重复且数量必须为 1–999");
            var drug=drugs.getDrug(requested.getDrugId());
            if(drug==null||!Objects.equals(drug.getStatus(),1)||!Objects.equals(drug.getApproveStatus(),1)
                    ||!Objects.equals(drug.getSaleableOnline(),1)||Objects.equals(drug.getDrugType(),3)||Objects.equals(drug.getIsSpecial(),1)||Objects.equals(drug.getIsPseudoephedrine(),1))
                throw invalidParamException("药品不支持当前在线处方申请");
            var item=new PrescItemVO();item.setDrugId(drug.getId());item.setQty(requested.getQty());
            item.setDrugName(drug.getGenericName());item.setSpecification(drug.getSpecification());items.add(item);
        }
        String no="APP-"+SecureUtil.sha256(TenantContextHolder.getTenantId()+"\n"+member+"\n"+req.getClientRequestId()).substring(0,28);
        String hash=SecureUtil.sha256(JsonUtils.toJsonString(List.of(req.getStoreId(),req.getPatientName().trim(),
                Objects.toString(req.getHospital(),""),Objects.toString(req.getDoctorName(),""),req.getMaterialIds(),items.stream().map(i->List.of(i.getDrugId(),i.getQty())).toList())));
        var existing=mapper.selectByPrescNo(no);
        if(existing!=null){if(!hash.equals(existing.getSubmissionHash()))throw invalidParamException("重复申请编号与原内容不一致");return existing.getId();}
        var save=new PrescRecordSaveReqVO();save.setPrescNo(no);save.setStoreId(req.getStoreId());save.setSource(0);
        save.setWxMemberId(member);save.setPatientName(req.getPatientName().trim());save.setHospital(req.getHospital());save.setDoctorName(req.getDoctorName());
        save.setImages(req.getMaterialIds().stream().map(id->"private:"+id).toList());save.setItems(items);save.setSubmissionHash(hash);
        try {return records.createPrescRecord(save);} catch(DuplicateKeyException ex) {
            existing=mapper.selectByPrescNo(no);if(existing==null||!hash.equals(existing.getSubmissionHash()))throw invalidParamException("重复申请冲突，请刷新记录");return existing.getId();
        } catch(cn.iocoder.yudao.framework.common.exception.ServiceException ex) {
            if(!Objects.equals(ex.getCode(),cn.iocoder.yudao.module.pharmacy.enums.ErrorCodeConstants.PRESC_NO_DUPLICATE.getCode()))throw ex;
            existing=mapper.selectByPrescNo(no);if(existing==null||!hash.equals(existing.getSubmissionHash()))throw invalidParamException("重复申请冲突，请刷新记录");return existing.getId();
        }
    }
}
