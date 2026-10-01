package cn.iocoder.yudao.module.pharmacy.dal.mysql.prescription;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.prescription.PrescriptionUseDO;
import org.apache.ibatis.annotations.Mapper;
@Mapper public interface PrescriptionUseMapper extends BaseMapperX<PrescriptionUseDO> {
    default PrescriptionUseDO active(Long prescId) {
        return selectOne(new LambdaQueryWrapperX<PrescriptionUseDO>().eq(PrescriptionUseDO::getPrescId,prescId)
                .in(PrescriptionUseDO::getStatus,"HELD","PAID"));
    }
    default PrescriptionUseDO order(Long orderId) {return selectOne(PrescriptionUseDO::getWxOrderId,orderId);}
}
