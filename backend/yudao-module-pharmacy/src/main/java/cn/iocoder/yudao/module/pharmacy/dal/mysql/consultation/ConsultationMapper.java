package cn.iocoder.yudao.module.pharmacy.dal.mysql.consultation;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.consultation.ConsultationDO;
import org.apache.ibatis.annotations.Mapper;
@Mapper public interface ConsultationMapper extends BaseMapperX<ConsultationDO> {
    default ConsultationDO lock(Long id) { return selectOne(new LambdaQueryWrapperX<ConsultationDO>().eq(ConsultationDO::getId,id).last("FOR UPDATE")); }
    default ConsultationDO owned(Long member,Long store,String kind) {
        return selectOne(new LambdaQueryWrapperX<ConsultationDO>().eq(ConsultationDO::getMemberId,member).eq(ConsultationDO::getStoreId,store).eq(ConsultationDO::getKind,kind));
    }
    default ConsultationDO ownedAfterConflict(Long member,Long store,String kind) {
        return selectOne(new LambdaQueryWrapperX<ConsultationDO>().eq(ConsultationDO::getMemberId,member).eq(ConsultationDO::getStoreId,store).eq(ConsultationDO::getKind,kind).last("FOR UPDATE"));
    }
}
