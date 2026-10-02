package cn.iocoder.yudao.module.ai.dal.mysql.pharmacy;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.ai.dal.dataobject.pharmacy.CustomerAiConsultDO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface CustomerAiConsultMapper extends BaseMapperX<CustomerAiConsultDO> {
    default CustomerAiConsultDO find(Long memberId, String clientMessageId) {
        return selectOne(new LambdaQueryWrapperX<CustomerAiConsultDO>()
                .eq(CustomerAiConsultDO::getMemberId, memberId)
                .eq(CustomerAiConsultDO::getClientMessageId, clientMessageId));
    }
    default int finish(Long id, String status, String result) {
        return update(null, new LambdaUpdateWrapper<CustomerAiConsultDO>()
                .eq(CustomerAiConsultDO::getId, id).eq(CustomerAiConsultDO::getStatus, "PENDING")
                .set(CustomerAiConsultDO::getStatus, status).set(CustomerAiConsultDO::getResultJson, result));
    }
}
