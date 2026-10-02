package cn.iocoder.yudao.module.ai.dal.mysql.pharmacy;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.ai.dal.dataobject.pharmacy.CustomerAiTopicDO;
import org.apache.ibatis.annotations.*;
@Mapper
public interface CustomerAiTopicMapper extends BaseMapperX<CustomerAiTopicDO> {
    @Select("SELECT * FROM ph_customer_ai_topic WHERE id=#{id} AND tenant_id=#{tenant} AND member_id=#{member} AND deleted=0 FOR UPDATE")
    CustomerAiTopicDO lock(@Param("id") Long id, @Param("tenant") Long tenant, @Param("member") Long member);
    @Delete("DELETE FROM ph_customer_ai_topic WHERE id=#{id} AND tenant_id=#{tenant} AND member_id=#{member}")
    int erase(@Param("id") Long id, @Param("tenant") Long tenant, @Param("member") Long member);
}
